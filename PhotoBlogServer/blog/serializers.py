from blog.models import Post, Comment
from rest_framework import serializers


class PostSerializer(serializers.ModelSerializer):
    author_username = serializers.CharField(source='author.username', read_only=True)
    recommendation_count = serializers.IntegerField(read_only=True, default=0)
    is_recommended = serializers.BooleanField(read_only=True, default=False)
    comment_count = serializers.IntegerField(read_only=True, default=0)

    class Meta:
        model = Post
        fields = ('id', 'author', 'author_username', 'title', 'text',
                  'created_date', 'published_date', 'image', 'recommendation_count', 'is_recommended', 'comment_count')
        read_only_fields = ('id', 'author', 'created_date', 'published_date')


class PostSearchSerializer(serializers.Serializer):
    search = serializers.CharField(required=False, default='', allow_blank=True, max_length=200)
    sort = serializers.ChoiceField(
        choices=('newest', 'oldest', 'recommendations', 'comments'),
        required=False, default='newest',
    )
    search_field = serializers.ChoiceField(
        choices=('title', 'author'), required=False, default='title',
    )

    def validate(self, attrs):
        # QueryDict treats an empty optional HTML value as omitted; reject an explicit empty sort.
        if self.initial_data.get('sort') == '':
            raise serializers.ValidationError({'sort': '정렬 방식을 선택해 주세요.'})
        return attrs


class CommentSerializer(serializers.ModelSerializer):
    author_username = serializers.CharField(source='author.username', read_only=True)
    text = serializers.CharField(max_length=1000, allow_blank=False)
    can_delete = serializers.SerializerMethodField()

    class Meta:
        model = Comment
        fields = ('id', 'post', 'author', 'author_username', 'text', 'created_date', 'can_delete')
        read_only_fields = ('id', 'post', 'author', 'created_date')

    def get_can_delete(self, obj):
        user = self.context['request'].user
        return user.is_authenticated and obj.author_id == user.pk
