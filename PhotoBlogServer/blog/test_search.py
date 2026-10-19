from django.contrib.auth import get_user_model
from django.utils import timezone
from rest_framework.test import APITestCase
from .models import Post

User = get_user_model()


class PostSearchTests(APITestCase):
    @classmethod
    def setUpTestData(cls):
        author = User.objects.create_user(username='river_writer')
        other = User.objects.create_user(username='other_writer')
        cls.title_match = Post.objects.create(author=other, title='서울 River 산책', text='강변 사진', published_date=timezone.now())
        cls.text_match = Post.objects.create(author=other, title='저녁 풍경', text='river 야경', published_date=timezone.now())
        cls.author_match = Post.objects.create(author=author, title='여행 사진', text='마을', published_date=timezone.now())
        cls.multiple_match = Post.objects.create(author=author, title='River 다리', text='river 사진', published_date=timezone.now())
        cls.literal_match = Post.objects.create(author=other, title="한글 & + 100%_ 사진 O'Reilly", text="O'Reilly", published_date=timezone.now())

    def search_ids(self, **params):
        response = self.client.get('/api_root/Post/', params)
        self.assertEqual(response.status_code, 200)
        self.assertIsInstance(response.data, list)
        self.assertEqual(response.headers['X-PhotoBlog-Search'], '1')
        return [post['id'] for post in response.data]

    def test_no_query_preserves_all_posts_and_array_response(self):
        self.assertEqual(len(self.search_ids()), 5)

    def test_title_search_ignores_matches_only_in_other_fields(self):
        self.assertCountEqual(self.search_ids(search='river', search_field='title'),
                              [self.title_match.pk, self.multiple_match.pk])

    def test_body_search_is_no_longer_supported(self):
        response = self.client.get('/api_root/Post/', {'search':'river', 'search_field':'text'})
        self.assertEqual(response.status_code, 400)
        self.assertIn('search_field', response.data)

    def test_author_search_uses_username_not_numeric_id(self):
        self.assertCountEqual(self.search_ids(search='river', search_field='author'),
                              [self.author_match.pk, self.multiple_match.pk])

    def test_default_search_matches_title_only(self):
        ids = self.search_ids(search='RIVER')
        self.assertCountEqual(ids, [self.title_match.pk, self.multiple_match.pk])
        self.assertEqual(len(ids), len(set(ids)))

    def test_korean_phrase_and_surrounding_whitespace(self):
        self.assertEqual(self.search_ids(search='  서울 River  ', search_field='title'), [self.title_match.pk])

    def test_empty_or_whitespace_query_returns_all(self):
        self.assertEqual(len(self.search_ids(search='')), 5)
        self.assertEqual(len(self.search_ids(search='   ')), 5)

    def test_no_matches_returns_200_empty_array(self):
        self.assertEqual(self.search_ids(search='찾을수없는문구'), [])

    def test_wildcards_and_url_reserved_chars_are_literal_text(self):
        self.assertEqual(self.search_ids(search='& + 100%_', search_field='title'), [self.literal_match.pk])
        self.assertEqual(self.search_ids(search="O'Reilly", search_field='title'), [self.literal_match.pk])

    def test_sql_like_input_is_treated_as_text(self):
        self.assertEqual(self.search_ids(search="' OR 1=1 --"), [])
        self.assertEqual(Post.objects.count(), 5)

    def test_invalid_field_is_rejected(self):
        for field in ('all', 'password'):
            with self.subTest(field=field):
                response = self.client.get('/api_root/Post/', {'search':'river','search_field':field})
                self.assertEqual(response.status_code, 400)
                self.assertIn('search_field', response.data)

    def test_overlong_search_is_rejected(self):
        response = self.client.get('/api_root/Post/', {'search':'a' * 201})
        self.assertEqual(response.status_code, 400)
        self.assertIn('search', response.data)
