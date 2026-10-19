from tempfile import TemporaryDirectory
from django.conf import settings
from rest_framework.test import APITestCase
from django.contrib.auth.models import User
from django.utils import timezone
from blog.models import Post
from django.core.files.uploadedfile import SimpleUploadedFile


class PostAPITests(APITestCase):
    def test_get_empty_post_list(self):
        response = self.client.get(
            "/api_root/Post/",
            HTTP_ACCEPT="application/json",
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), [])

    def test_get_post_list_with_one_post(self):
        author = User.objects.create_user(username="test_author")

        post = Post.objects.create(
            author=author,
            title="조회 테스트 게시물",
            text="테스트 DB에 저장한 글입니다.",
            published_date=timezone.now(),
            image="images/pig.png",
        )

        response = self.client.get(
            "/api_root/Post/",
            HTTP_ACCEPT="application/json",
        )

        self.assertEqual(response.status_code, 200)

        data = response.json()
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["title"], post.title)
        self.assertEqual(
            data[0]["image"],
            "http://testserver/media/images/pig.png",
        )
        
    def test_post_creates_post(self):
        author = User.objects.create_user(username="post_author")
        self.client.force_authenticate(user=author)

        payload = {
            "author": author.pk,
            "title": "POST 자동 테스트",
            "text": "요청한 내용이 DB에 저장되는지 확인합니다.",
            "published_date": timezone.now().isoformat(),
        }

        response = self.client.post(
            "/api_root/Post/",
            payload,
            format="json",
            HTTP_ACCEPT="application/json",
        )

        self.assertEqual(response.status_code, 201)
        self.assertEqual(Post.objects.count(), 1)

        saved_post = Post.objects.get()
        self.assertEqual(saved_post.title, payload["title"])
        self.assertEqual(saved_post.text, payload["text"])
        self.assertEqual(saved_post.author_id, author.pk)
        self.assertEqual(response.json()["title"], payload["title"])
        
    def test_post_creates_post_with_image(self):
        author = User.objects.create_user(username="image_author")
        self.client.force_authenticate(user=author)
        image_path = settings.BASE_DIR / "media" / "images" / "pig.png"

        with TemporaryDirectory() as media_root:
            with self.settings(MEDIA_ROOT=media_root):
                with image_path.open("rb") as image_file:
                    payload = {
                        "author": author.pk,
                        "title": "이미지 업로드 테스트",
                        "text": "이미지 파일의 저장을 확인합니다.",
                        "published_date": timezone.now().isoformat(),
                        "image": image_file,
                    }

                    response = self.client.post(
                        "/api_root/Post/",
                        payload,
                        format="multipart",
                        HTTP_ACCEPT="application/json",
                    )

                self.assertEqual(response.status_code, 201)
                self.assertEqual(Post.objects.count(), 1)

                saved_post = Post.objects.get()
                self.assertEqual(saved_post.title, payload["title"])
                self.assertTrue(
                    saved_post.image.storage.exists(saved_post.image.name)
                )

                with saved_post.image.open("rb") as saved_image:
                    self.assertEqual(
                        saved_image.read(),
                        image_path.read_bytes(),
                    )
    def test_post_without_title_is_rejected(self):
        author = User.objects.create_user(username="invalid_author")
        self.client.force_authenticate(user=author)

        payload = {
            "author": author.pk,
            "text": "제목을 생략한 요청입니다.",
            "published_date": timezone.now().isoformat(),
        }

        response = self.client.post(
            "/api_root/Post/",
            payload,
            format="json",
            HTTP_ACCEPT="application/json",
        )

        self.assertEqual(response.status_code, 400)
        self.assertIn("title", response.json())
        self.assertEqual(Post.objects.count(), 0)
        
    def test_post_rejects_invalid_image(self):
        author = User.objects.create_user(username="invalid_image_author")
        self.client.force_authenticate(user=author)

        image_file = SimpleUploadedFile(
            "fake.png",
            b"this is not a real image",
            content_type="image/png",
        )

        payload = {
            "author": author.pk,
            "title": "잘못된 이미지 테스트",
            "text": "이미지가 아닌 파일은 거절되어야 합니다.",
            "published_date": timezone.now().isoformat(),
            "image": image_file,
        }

        with TemporaryDirectory() as media_root:
            with self.settings(MEDIA_ROOT=media_root):
                response = self.client.post(
                    "/api_root/Post/",
                    payload,
                    format="multipart",
                    HTTP_ACCEPT="application/json",
                )

                self.assertEqual(response.status_code, 400)
                self.assertIn("image", response.json())
                self.assertEqual(Post.objects.count(), 0)
