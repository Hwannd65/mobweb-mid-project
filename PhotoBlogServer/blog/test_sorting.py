from datetime import timedelta
from django.contrib.auth import get_user_model
from django.utils import timezone
from rest_framework.authtoken.models import Token
from rest_framework.test import APITestCase
from .models import Post, Recommendation, Comment


class PostSortingTests(APITestCase):
    @classmethod
    def setUpTestData(cls):
        cls.author = get_user_model().objects.create_user(username='sort_author')
        cls.reader = get_user_model().objects.create_user(username='sort_reader')
        start = timezone.now() - timedelta(days=3)
        cls.old = Post.objects.create(author=cls.author, title='정렬 오래된 사진', created_date=start)
        cls.middle = Post.objects.create(author=cls.author, title='정렬 중간 사진', created_date=start + timedelta(days=1))
        cls.new = Post.objects.create(author=cls.reader, title='정렬 새 사진', created_date=start + timedelta(days=2))
        cls.tie = Post.objects.create(author=cls.author, title='정렬 같은 시각', created_date=cls.new.created_date)
        # Multiple joins must not multiply either aggregate: old has 2 recommendations / 1 comment.
        Recommendation.objects.create(post=cls.old, user=cls.author)
        Recommendation.objects.create(post=cls.old, user=cls.reader)
        Recommendation.objects.create(post=cls.middle, user=cls.reader)
        Recommendation.objects.create(post=cls.new, user=cls.reader)
        Comment.objects.create(post=cls.old, author=cls.reader, text='one')
        for text in ('one', 'two', 'three'):
            Comment.objects.create(post=cls.middle, author=cls.reader, text=text)
        Comment.objects.create(post=cls.new, author=cls.reader, text='one')

    def ordered(self, sort=None, **params):
        if sort is not None: params['sort'] = sort
        response = self.client.get('/api_root/Post/', params)
        self.assertEqual(response.status_code, 200)
        self.assertIsInstance(response.data, list)
        self.assertEqual(response['X-PhotoBlog-Sort'], sort or 'newest')
        self.assertEqual(response['X-PhotoBlog-Search'], '1')
        return [p['id'] for p in response.data]

    def test_default_is_newest_with_id_tie_break(self):
        self.assertEqual(self.ordered(), [self.tie.pk, self.new.pk, self.middle.pk, self.old.pk])

    def test_explicit_newest_and_oldest_are_deterministic(self):
        self.assertEqual(self.ordered('newest'), [self.tie.pk, self.new.pk, self.middle.pk, self.old.pk])
        self.assertEqual(self.ordered('oldest'), [self.old.pk, self.middle.pk, self.new.pk, self.tie.pk])

    def test_recommendations_descending_then_creation_then_id(self):
        self.assertEqual(self.ordered('recommendations'), [self.old.pk, self.new.pk, self.middle.pk, self.tie.pk])

    def test_comments_descending_then_creation_then_id(self):
        self.assertEqual(self.ordered('comments'), [self.middle.pk, self.new.pk, self.old.pk, self.tie.pk])

    def test_zero_count_ties_use_newest_then_id(self):
        Recommendation.objects.all().delete()
        Comment.objects.all().delete()
        for sort in ('recommendations', 'comments'):
            self.assertEqual(self.ordered(sort), [self.tie.pk, self.new.pk, self.middle.pk, self.old.pk])

    def test_title_and_author_search_can_be_sorted(self):
        self.assertEqual(self.ordered('oldest', search='정렬', search_field='title'),
                         [self.old.pk, self.middle.pk, self.new.pk, self.tie.pk])
        self.assertEqual(self.ordered('comments', search='sort_author', search_field='author'),
                         [self.middle.pk, self.old.pk, self.tie.pk])
        self.assertEqual(self.ordered('recommendations', search='검색결과없음'), [])

    def test_invalid_public_names_and_database_fields_are_rejected(self):
        for sort in ('', 'random', '-created_date', 'author__password', "newest; DROP TABLE blog_post"):
            with self.subTest(sort=sort):
                response = self.client.get('/api_root/Post/', {'sort':sort})
                self.assertEqual(response.status_code, 400)
                self.assertIn('sort', response.data)
        self.assertEqual(Post.objects.count(), 4)

    def test_counts_stay_distinct_and_personal_flag_is_correct(self):
        token = Token.objects.create(user=self.author)
        self.client.credentials(HTTP_AUTHORIZATION='Token ' + token.key)
        response = self.client.get('/api_root/Post/', {'sort':'recommendations'})
        self.assertEqual(response.status_code, 200)
        old = response.data[0]
        self.assertEqual((old['recommendation_count'], old['comment_count']), (2, 1))
        self.assertTrue(old['is_recommended'])
        self.assertFalse(response.data[1]['is_recommended'])
        self.assertIn('Authorization', response['Vary'])
        self.assertIn('no-store', response['Cache-Control'])

    def test_fresh_get_reflects_recommendation_and_comment_removal(self):
        Recommendation.objects.filter(post=self.old).delete()
        self.assertEqual(self.ordered('recommendations')[0], self.new.pk)
        Comment.objects.filter(post=self.middle).delete()
        self.assertEqual(self.ordered('comments')[0], self.new.pk)

    def test_registration_time_not_last_publication_edit_controls_order(self):
        self.old.published_date = timezone.now() + timedelta(days=1)
        self.old.save(update_fields=['published_date'])
        self.assertEqual(self.ordered('newest')[-1], self.old.pk)
        self.new.created_date = self.old.created_date
        self.new.save(update_fields=['created_date'])
        self.assertEqual(self.ordered('oldest')[:2], [self.old.pk, self.new.pk])
