from django.contrib.auth import get_user_model
from django.db import IntegrityError, transaction
from rest_framework.authtoken.models import Token
from rest_framework.test import APITestCase
from .models import Post, Recommendation

User = get_user_model()


class RecommendationTests(APITestCase):
    def setUp(self):
        self.author = User.objects.create_user(username='post_owner')
        self.reader = User.objects.create_user(username='reader')
        self.other = User.objects.create_user(username='another_reader')
        self.post = Post.objects.create(author=self.author, title='추천 대상', text='사진')
        self.url = f'/api_root/Post/{self.post.pk}/recommendation/'

    def authenticate(self, user):
        token, _ = Token.objects.get_or_create(user=user)
        self.client.credentials(HTTP_AUTHORIZATION='Token ' + token.key)
        return token

    def test_anonymous_cannot_recommend_or_cancel(self):
        self.assertEqual(self.client.put(self.url, {}).status_code, 401)
        self.assertEqual(self.client.delete(self.url).status_code, 401)
        self.assertFalse(Recommendation.objects.exists())

    def test_non_author_can_recommend_and_retries_are_idempotent(self):
        self.authenticate(self.reader)
        for _ in range(2):
            response = self.client.put(self.url, {}, format='json')
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.data, {'id':self.post.pk,'recommendation_count':1,'is_recommended':True})
        self.assertEqual(Recommendation.objects.count(), 1)
        self.assertEqual(Recommendation.objects.get().user, self.reader)

    def test_user_cannot_impersonate_another_recommender(self):
        self.authenticate(self.reader)
        response = self.client.put(self.url, {'user':self.other.pk}, format='json')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(Recommendation.objects.get().user, self.reader)

    def test_independent_users_each_count_once(self):
        self.authenticate(self.reader)
        self.client.put(self.url, {})
        self.authenticate(self.other)
        response = self.client.put(self.url, {})
        self.assertEqual(response.data['recommendation_count'], 2)
        self.assertTrue(response.data['is_recommended'])

    def test_cancel_only_removes_request_users_recommendation_and_can_retry(self):
        Recommendation.objects.create(post=self.post, user=self.other)
        self.authenticate(self.reader)
        self.client.put(self.url, {})
        for _ in range(2):
            response = self.client.delete(self.url)
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.data['recommendation_count'], 1)
            self.assertFalse(response.data['is_recommended'])
        self.assertEqual(Recommendation.objects.get().user, self.other)

    def test_list_count_is_global_but_flag_is_per_user(self):
        Recommendation.objects.create(post=self.post, user=self.reader)
        for user, expected in [(None, False), (self.reader, True), (self.other, False)]:
            with self.subTest(user=user):
                self.client.credentials()
                if user: self.authenticate(user)
                response = self.client.get('/api_root/Post/')
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.data[0]['recommendation_count'], 1)
                self.assertEqual(response.data[0]['is_recommended'], expected)
                self.assertIn('Authorization', response.headers['Vary'])
                self.assertIn('no-store', response.headers['Cache-Control'])

    def test_search_preserves_count_and_my_flag(self):
        self.authenticate(self.reader)
        self.client.put(self.url, {})
        response = self.client.get('/api_root/Post/', {'search':'추천','search_field':'title'})
        self.assertEqual(len(response.data), 1)
        self.assertEqual(response.data[0]['recommendation_count'], 1)
        self.assertTrue(response.data[0]['is_recommended'])

    def test_database_unique_constraint_prevents_duplicates_outside_api(self):
        Recommendation.objects.create(post=self.post, user=self.reader)
        with self.assertRaises(IntegrityError), transaction.atomic():
            Recommendation.objects.create(post=self.post, user=self.reader)
        self.assertEqual(Recommendation.objects.count(), 1)

    def test_missing_post_returns_404(self):
        self.authenticate(self.reader)
        self.assertEqual(self.client.put('/api_root/Post/999999/recommendation/', {}).status_code, 404)
        self.assertFalse(Recommendation.objects.exists())

    def test_invalid_token_cannot_recommend(self):
        self.client.credentials(HTTP_AUTHORIZATION='Token invalid')
        self.assertEqual(self.client.put(self.url, {}).status_code, 401)

    def test_deleting_post_cascades_recommendations(self):
        Recommendation.objects.create(post=self.post, user=self.reader)
        self.authenticate(self.author)
        self.assertEqual(self.client.delete(f'/api_root/Post/{self.post.pk}/').status_code, 204)
        self.assertFalse(Recommendation.objects.exists())

    def test_counts_and_personal_flag_are_read_only(self):
        self.authenticate(self.author)
        response = self.client.patch(f'/api_root/Post/{self.post.pk}/',
                                    {'recommendation_count':500,'is_recommended':True}, format='json')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data['recommendation_count'], 0)
        self.assertFalse(response.data['is_recommended'])
        self.assertFalse(Recommendation.objects.exists())

    def test_recommendation_does_not_grant_post_edit_permission(self):
        self.authenticate(self.reader)
        self.client.put(self.url, {})
        self.assertEqual(self.client.patch(f'/api_root/Post/{self.post.pk}/', {'title':'Stolen'}).status_code, 403)
