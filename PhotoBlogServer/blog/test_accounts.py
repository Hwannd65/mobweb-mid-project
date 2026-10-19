from unittest.mock import patch
from django.contrib.auth import get_user_model
from django.core.cache import cache
from django.utils import timezone
from rest_framework.authtoken.models import Token
from rest_framework.test import APITestCase
from rest_framework.throttling import ScopedRateThrottle
from .models import Post

User = get_user_model()
PASSWORD = 'River!Quartz-29-Sun'


class AccountAPITests(APITestCase):
    def setUp(self):
        cache.clear()
        self.payload = {'username': 'new_reader', 'password': PASSWORD, 'password_confirm': PASSWORD}

    def register(self, **changes):
        return self.client.post('/api/register/', {**self.payload, **changes}, format='json')

    def test_register_hashes_password_and_returns_only_public_fields(self):
        response = self.register(is_staff=True, is_superuser=True)
        self.assertEqual(response.status_code, 201)
        self.assertEqual(set(response.data), {'id', 'username'})
        user = User.objects.get(username='new_reader')
        self.assertNotEqual(user.password, PASSWORD)
        self.assertTrue(user.check_password(PASSWORD))
        self.assertFalse(user.is_staff)
        self.assertFalse(user.is_superuser)
        self.assertFalse(Token.objects.filter(user=user).exists())

    def test_password_confirmation_must_match(self):
        response = self.register(password_confirm='another-password')
        self.assertEqual(response.status_code, 400)
        self.assertIn('password_confirm', response.data)
        self.assertFalse(User.objects.exists())

    def test_weak_password_is_rejected(self):
        response = self.register(password='12345678', password_confirm='12345678')
        self.assertEqual(response.status_code, 400)
        self.assertIn('password', response.data)
        self.assertFalse(User.objects.exists())

    def test_short_password_is_rejected(self):
        self.assertEqual(self.register(password='xY!2', password_confirm='xY!2').status_code, 400)
        self.assertFalse(User.objects.exists())

    def test_duplicate_username_is_rejected(self):
        self.assertEqual(self.register().status_code, 201)
        self.assertEqual(self.register().status_code, 400)
        self.assertEqual(User.objects.count(), 1)

    def test_missing_required_fields_are_rejected(self):
        response = self.client.post('/api/register/', {}, format='json')
        self.assertEqual(response.status_code, 400)
        self.assertEqual(set(response.data), {'username', 'password', 'password_confirm'})
        self.assertFalse(User.objects.exists())

    def test_racing_duplicate_does_not_return_server_error(self):
        from django.db import IntegrityError
        with patch.object(User.objects, 'create_user', side_effect=IntegrityError('duplicate')):
            response = self.register()
        self.assertEqual(response.status_code, 400)
        self.assertIn('username', response.data)

    def test_signup_is_rate_limited(self):
        with patch.dict(ScopedRateThrottle.THROTTLE_RATES, {'signup': '1/min'}):
            self.assertEqual(self.register().status_code, 201)
            self.assertEqual(self.register(username='second_reader').status_code, 429)
        self.assertEqual(User.objects.count(), 1)

    def test_login_me_logout_invalidates_previous_token(self):
        self.register()
        response = self.client.post('/api-token-auth/',
                                    {'username': 'new_reader', 'password': PASSWORD}, format='json')
        self.assertEqual(response.status_code, 200)
        token = response.data['token']
        self.client.credentials(HTTP_AUTHORIZATION='Token ' + token)
        response = self.client.get('/api/me/')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data['username'], 'new_reader')
        self.assertEqual(self.client.post('/api/logout/', {}, format='json').status_code, 204)
        self.assertFalse(Token.objects.exists())
        self.assertEqual(self.client.get('/api/me/').status_code, 401)
        self.client.credentials()
        new_login = self.client.post('/api-token-auth/',
                                     {'username': 'new_reader', 'password': PASSWORD}, format='json')
        self.assertEqual(new_login.status_code, 200)
        self.assertNotEqual(new_login.data['token'], token)

    def test_wrong_password_returns_400_and_no_token(self):
        self.register()
        response = self.client.post('/api-token-auth/',
                                    {'username': 'new_reader', 'password': 'wrong'}, format='json')
        self.assertEqual(response.status_code, 400)
        self.assertFalse(Token.objects.exists())

    def test_anonymous_and_invalid_token_cannot_read_identity_or_logout(self):
        self.assertEqual(self.client.get('/api/me/').status_code, 401)
        self.assertEqual(self.client.post('/api/logout/', {}).status_code, 401)
        self.client.credentials(HTTP_AUTHORIZATION='Token invalid')
        self.assertEqual(self.client.get('/api/me/').status_code, 401)
        self.assertEqual(self.client.post('/api/logout/', {}).status_code, 401)

    def test_browser_session_cannot_substitute_for_logout_token(self):
        user = User.objects.create_user(username='session_reader', password=PASSWORD)
        self.client.force_login(user)
        self.assertEqual(self.client.post('/api/logout/', {}).status_code, 401)


class PostAuthorizationTests(APITestCase):
    def setUp(self):
        self.owner = User.objects.create_user(username='owner')
        self.other = User.objects.create_user(username='other')
        self.token = Token.objects.create(user=self.owner)
        self.post = Post.objects.create(author=self.owner, title='Original', text='Body',
                                        published_date=timezone.now())
        self.detail = f'/api_root/Post/{self.post.pk}/'

    def authenticate(self, user):
        token, _ = Token.objects.get_or_create(user=user)
        self.client.credentials(HTTP_AUTHORIZATION='Token ' + token.key)

    def test_anonymous_can_read_but_cannot_create_update_delete(self):
        self.assertEqual(self.client.get('/api_root/Post/').status_code, 200)
        self.assertEqual(self.client.get(self.detail).status_code, 200)
        self.assertEqual(self.client.post('/api_root/Post/', {'title':'X','text':'Y'}).status_code, 401)
        self.assertEqual(self.client.patch(self.detail, {'title':'X'}).status_code, 401)
        self.assertEqual(self.client.delete(self.detail).status_code, 401)
        self.assertEqual(Post.objects.count(), 1)

    def test_create_uses_token_owner_and_server_timestamp(self):
        self.authenticate(self.owner)
        response = self.client.post('/api_root/Post/', {
            'author': self.other.pk, 'title': 'Token owner', 'text': 'Body',
            'created_date': '2000-01-01T00:00:00Z', 'published_date': '2000-01-01T00:00:00Z',
        }, format='json')
        self.assertEqual(response.status_code, 201)
        post = Post.objects.get(pk=response.data['id'])
        self.assertEqual(post.author, self.owner)
        self.assertEqual(response.data['author_username'], 'owner')
        self.assertGreater(post.created_date.year, 2000)
        self.assertGreater(post.published_date.year, 2000)

    def test_other_user_cannot_update_or_delete(self):
        self.authenticate(self.other)
        self.assertEqual(self.client.patch(self.detail, {'title':'Stolen'}).status_code, 403)
        self.assertEqual(self.client.delete(self.detail).status_code, 403)
        self.post.refresh_from_db()
        self.assertEqual(self.post.title, 'Original')

    def test_owner_can_update_but_cannot_transfer_authorship(self):
        self.authenticate(self.owner)
        response = self.client.patch(self.detail, {'title':'Edited','author':self.other.pk}, format='json')
        self.assertEqual(response.status_code, 200)
        self.post.refresh_from_db()
        self.assertEqual(self.post.title, 'Edited')
        self.assertEqual(self.post.author, self.owner)

    def test_owner_can_delete(self):
        self.authenticate(self.owner)
        self.assertEqual(self.client.delete(self.detail).status_code, 204)
        self.assertFalse(Post.objects.exists())

    def test_revoked_token_cannot_create(self):
        self.authenticate(self.owner)
        self.token.delete()
        self.assertEqual(self.client.post('/api_root/Post/', {'title':'X','text':'Y'}).status_code, 401)

    def test_web_edit_also_checks_owner(self):
        self.client.force_login(self.other)
        self.assertEqual(self.client.post(f'/post/{self.post.pk}/edit/',
                                         {'title':'Stolen','text':'X'}).status_code, 403)
        self.post.refresh_from_db()
        self.assertEqual(self.post.title, 'Original')

    def test_anonymous_web_create_redirects_to_login(self):
        response = self.client.get('/post/new/')
        self.assertEqual(response.status_code, 302)
        self.assertTrue(response.url.startswith('/admin/login/'))
