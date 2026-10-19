from datetime import timedelta
from django.contrib.auth import get_user_model
from django.utils import timezone
from rest_framework.authtoken.models import Token
from rest_framework.test import APITestCase
from .models import Comment, Post, Recommendation

User = get_user_model()


class CommentTests(APITestCase):
    def setUp(self):
        self.owner = User.objects.create_user(username='post_owner')
        self.reader = User.objects.create_user(username='reader')
        self.other = User.objects.create_user(username='other')
        self.post = Post.objects.create(author=self.owner, title='댓글 대상', text='사진')
        self.other_post = Post.objects.create(author=self.owner, title='다른 글', text='다른 사진')
        self.url = f'/api_root/Post/{self.post.pk}/comments/'

    def authenticate(self, user):
        token, _ = Token.objects.get_or_create(user=user)
        self.client.credentials(HTTP_AUTHORIZATION='Token '+token.key)

    def delete_url(self, comment, post=None):
        return f'/api_root/Post/{(post or self.post).pk}/comments/{comment.pk}/'

    def test_anonymous_can_read_empty_list_but_cannot_write(self):
        response = self.client.get(self.url)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data, [])
        self.assertEqual(self.client.post(self.url, {'text':'안녕'}).status_code, 401)
        self.assertFalse(Comment.objects.exists())

    def test_create_uses_token_author_and_url_post(self):
        self.authenticate(self.reader)
        response = self.client.post(self.url, {'text':'  멋진 사진입니다!  ', 'author':self.other.pk,
                                               'post':self.other_post.pk,
                                               'created_date':'2000-01-01T00:00:00Z'}, format='json')
        self.assertEqual(response.status_code, 201)
        comment = Comment.objects.get()
        self.assertEqual(comment.author, self.reader)
        self.assertEqual(comment.post, self.post)
        self.assertEqual(comment.text, '멋진 사진입니다!')
        self.assertGreater(comment.created_date.year, 2000)
        self.assertEqual(response.data['author_username'], 'reader')
        self.assertTrue(response.data['can_delete'])
        self.assertEqual(response.data['comment_count'], 1)

    def test_missing_empty_whitespace_and_overlong_text_are_rejected(self):
        self.authenticate(self.reader)
        for payload in ({}, {'text':''}, {'text':' \n '}, {'text':'a'*1001}):
            with self.subTest(payload_length=len(payload.get('text',''))):
                response = self.client.post(self.url, payload, format='json')
                self.assertEqual(response.status_code, 400)
                self.assertIn('text', response.data)
        self.assertFalse(Comment.objects.exists())

    def test_comment_limit_and_literal_markup(self):
        self.authenticate(self.reader)
        self.assertEqual(self.client.post(self.url, {'text':'a'*1000}, format='json').status_code, 201)
        text = "<script>alert('x')</script> 한글 & + 100%"
        self.assertEqual(self.client.post(self.url, {'text':text}, format='json').status_code, 201)
        self.assertEqual(Comment.objects.order_by('pk').last().text, text)

    def test_list_is_scoped_to_post_and_ordered_oldest_first(self):
        old = timezone.now()-timedelta(days=1)
        a = Comment.objects.create(post=self.post, author=self.reader, text='first', created_date=old)
        b = Comment.objects.create(post=self.post, author=self.other, text='second')
        Comment.objects.create(post=self.other_post, author=self.reader, text='not included')
        response = self.client.get(self.url)
        self.assertEqual([row['id'] for row in response.data], [a.pk,b.pk])
        self.assertEqual(response.data[0]['author_username'], 'reader')

    def test_can_delete_is_personal_and_responses_are_not_shared_cached(self):
        a = Comment.objects.create(post=self.post, author=self.reader, text='mine')
        b = Comment.objects.create(post=self.post, author=self.other, text='other')
        for user, flags in [(None,[False,False]),(self.reader,[True,False]),(self.other,[False,True])]:
            with self.subTest(user=user):
                self.client.credentials()
                if user: self.authenticate(user)
                response = self.client.get(self.url)
                self.assertEqual([row['can_delete'] for row in response.data], flags)
                self.assertIn('Authorization', response.headers['Vary'])
                self.assertIn('no-store', response.headers['Cache-Control'])

    def test_author_can_delete_own_comment(self):
        comment = Comment.objects.create(post=self.post, author=self.reader, text='mine')
        self.authenticate(self.reader)
        self.assertEqual(self.client.delete(self.delete_url(comment)).status_code, 204)
        self.assertFalse(Comment.objects.exists())
        self.assertEqual(self.client.get(f'/api_root/Post/{self.post.pk}/').data['comment_count'], 0)

    def test_other_user_and_post_owner_cannot_delete_readers_comment(self):
        comment = Comment.objects.create(post=self.post, author=self.reader, text='mine')
        for user in (self.other, self.owner):
            self.authenticate(user)
            self.assertEqual(self.client.delete(self.delete_url(comment)).status_code, 403)
        self.assertTrue(Comment.objects.filter(pk=comment.pk).exists())

    def test_guest_cannot_delete(self):
        comment = Comment.objects.create(post=self.post, author=self.reader, text='mine')
        self.assertEqual(self.client.delete(self.delete_url(comment)).status_code, 401)
        self.assertTrue(Comment.objects.exists())

    def test_nested_delete_cannot_target_comment_under_wrong_post(self):
        comment = Comment.objects.create(post=self.post, author=self.reader, text='mine')
        self.authenticate(self.reader)
        self.assertEqual(self.client.delete(self.delete_url(comment,self.other_post)).status_code, 404)
        self.assertTrue(Comment.objects.exists())

    def test_missing_parent_or_comment_returns_404(self):
        self.assertEqual(self.client.get('/api_root/Post/999999/comments/').status_code, 404)
        self.authenticate(self.reader)
        self.assertEqual(self.client.post('/api_root/Post/999999/comments/', {'text':'no'}).status_code, 404)
        self.assertEqual(self.client.delete(self.url+'999999/').status_code, 404)

    def test_post_comment_and_recommendation_counts_do_not_multiply(self):
        for user in (self.reader,self.other):
            Recommendation.objects.create(post=self.post, user=user)
        for i in range(3):
            Comment.objects.create(post=self.post, author=self.reader, text=str(i))
        response = self.client.get(f'/api_root/Post/{self.post.pk}/')
        self.assertEqual(response.data['recommendation_count'], 2)
        self.assertEqual(response.data['comment_count'], 3)

    def test_count_is_read_only(self):
        self.authenticate(self.owner)
        response = self.client.patch(f'/api_root/Post/{self.post.pk}/', {'comment_count':99}, format='json')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data['comment_count'], 0)

    def test_post_deletion_cascades_comments(self):
        Comment.objects.create(post=self.post, author=self.reader, text='mine')
        self.authenticate(self.owner)
        self.assertEqual(self.client.delete(f'/api_root/Post/{self.post.pk}/').status_code, 204)
        self.assertFalse(Comment.objects.exists())

    def test_comment_deletion_does_not_grant_post_edit_permission(self):
        comment = Comment.objects.create(post=self.post, author=self.reader, text='mine')
        self.authenticate(self.reader)
        self.client.delete(self.delete_url(comment))
        self.assertEqual(self.client.patch(f'/api_root/Post/{self.post.pk}/', {'title':'stolen'}).status_code, 403)
