"""Prepare photo-blog demonstration data in an empty, isolated local database."""
from pathlib import Path
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
from django.conf import settings
from django.contrib.auth import get_user_model
from django.core.files import File
from django.db import transaction
from blog.models import Post, Recommendation, Comment

root=Path(__file__).resolve().parents[1]
# The actual student DB and cloud production settings are never valid targets.
db=Path(settings.DATABASES['default']['NAME']).resolve()
assert db != root/'db.sqlite3' and settings.SETTINGS_MODULE=='demo_settings', 'Use isolated demo_settings only'
assert Path(settings.MEDIA_ROOT).resolve() != (root/'media').resolve(), 'Use an isolated media folder'
assert not Post.objects.exists(), 'The demonstration database must be empty'
pictures=root.parent/'pictures'
with transaction.atomic():
    users={}
    for name in ['minsu','jiyun','photo_friend']:
        user=get_user_model().objects.create_user(username=name)
        user.set_unusable_password();user.save(update_fields=['password'])
        users[name]=user
    specs=[
        ('jiyun','태극기','태극기 이미지입니다.','Korea.jpg',2,['태극기가 선명하네요.']),
        ('minsu','기아 타이거즈','기아 타이거즈 로고입니다.','KIA_Tigers.png',1,['저도 기아 팬이에요.','로고 멋지네요.','이미지가 잘 보이네요.']),
        ('jiyun','LA 레이커스','레이커스 로고도 올려봅니다.','LA_Lakers.jpg',1,['색 조합이 예쁘네요.']),
    ]
    start=datetime(2026,10,17,10,0,tzinfo=ZoneInfo('Asia/Seoul'))
    result=[]
    for i,(author,title,text,filename,votes,comments) in enumerate(specs):
        time=start+timedelta(days=i)
        post=Post(author=users[author],title=title,text=text,created_date=time,published_date=time)
        with (pictures/filename).open('rb') as f:post.image.save(filename,File(f),save=False)
        post.save()
        for name in ['photo_friend','minsu'][:votes]:Recommendation.objects.create(post=post,user=users[name])
        for j,text in enumerate(comments):Comment.objects.create(post=post,author=users[['photo_friend','jiyun','minsu'][j%3]],text=text,created_date=time+timedelta(hours=j+1))
        result.append({'title':title,'author':author,'recommendations':votes,'comments':len(comments)})
print(result)
