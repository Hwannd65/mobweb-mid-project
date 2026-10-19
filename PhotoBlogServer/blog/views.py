from django.shortcuts import render, get_object_or_404, redirect
from django.utils import timezone
from django.db.models import Q, Count, Exists, OuterRef, Value, BooleanField
from django.utils.cache import patch_vary_headers
from .models import Post, Recommendation, Comment
from .forms import PostForm
from rest_framework import viewsets, status
from rest_framework.permissions import IsAuthenticatedOrReadOnly, IsAuthenticated
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.exceptions import PermissionDenied as APIPermissionDenied
from django.contrib.auth.decorators import login_required
from django.core.exceptions import PermissionDenied
from .permissions import IsAuthorOrReadOnly
from .serializers import PostSerializer, PostSearchSerializer, CommentSerializer

def post_list(request):
    posts = Post.objects.filter(
        published_date__lte=timezone.now()
    ).order_by('published_date')
    return render(request, 'blog/post_list.html', {'posts': posts})

def post_detail(request, pk):
    post = get_object_or_404(Post, pk=pk)
    return render(request, 'blog/post_detail.html', {'post': post})

@login_required(login_url='/admin/login/')
def post_new(request):
    if request.method == "POST":
        form = PostForm(request.POST)
        if form.is_valid():
            post = form.save(commit=False)
            post.author = request.user
            post.published_date = timezone.now()
            post.save()
            return redirect('post_detail', pk=post.pk)
    else:
        form = PostForm()
    return render(request, 'blog/post_edit.html', {'form': form})

@login_required(login_url='/admin/login/')
def post_edit(request, pk):
    post = get_object_or_404(Post, pk=pk)
    if post.author_id != request.user.pk:
        raise PermissionDenied
    if request.method == "POST":
        form = PostForm(request.POST, instance=post)
        if form.is_valid():
            post = form.save(commit=False)
            post.author = request.user
            post.published_date = timezone.now()
            post.save()
            return redirect('post_detail', pk=post.pk)
    else:
        form = PostForm(instance=post)
    return render(request, 'blog/post_edit.html', {'form': form})

def js_test(request):
    return render(request, 'blog/js_test.html', {})

class IntruderImage(viewsets.ModelViewSet):
    queryset = Post.objects.select_related('author').all()
    serializer_class = PostSerializer
    permission_classes = [IsAuthenticatedOrReadOnly, IsAuthorOrReadOnly]

    def finalize_response(self, request, response, *args, **kwargs):
        response = super().finalize_response(request, response, *args, **kwargs)
        # The same post list has a different "my recommendation" flag for each token.
        patch_vary_headers(response, ['Authorization'])
        response['Cache-Control'] = 'private, no-store'
        return response

    def list(self, request, *args, **kwargs):
        response = super().list(request, *args, **kwargs)
        response['X-PhotoBlog-Search'] = '1'
        response['X-PhotoBlog-Sort'] = request.query_params.get('sort', 'newest')
        return response

    def get_queryset(self):
        queryset = super().get_queryset().annotate(
            recommendation_count=Count('recommendations', distinct=True),
            comment_count=Count('comments', distinct=True),
        )
        if self.request.user.is_authenticated:
            mine = Recommendation.objects.filter(post_id=OuterRef('pk'), user=self.request.user)
            queryset = queryset.annotate(is_recommended=Exists(mine))
        else:
            queryset = queryset.annotate(is_recommended=Value(False, output_field=BooleanField()))
        if self.action != 'list':
            return queryset
        params = PostSearchSerializer(data=self.request.query_params)
        params.is_valid(raise_exception=True)
        keyword = params.validated_data['search']
        field = params.validated_data['search_field']
        # Only these public sort names can select database ordering fields.
        orderings = {
            'newest': ('-created_date', '-pk'),
            'oldest': ('created_date', 'pk'),
            'recommendations': ('-recommendation_count', '-created_date', '-pk'),
            'comments': ('-comment_count', '-created_date', '-pk'),
        }
        queryset = queryset.order_by(*orderings[params.validated_data['sort']])
        if not keyword:
            return queryset
        # These field names are chosen by the server, never interpolated from user input.
        conditions = {
            'author': Q(author__username__icontains=keyword),
            'title': Q(title__icontains=keyword),
        }
        return queryset.filter(conditions[field])

    def perform_create(self, serializer):
        # Client-supplied author IDs cannot impersonate another user.
        serializer.save(author=self.request.user, published_date=timezone.now())

    @action(detail=True, methods=['put', 'delete'], permission_classes=[IsAuthenticated])
    def recommendation(self, request, pk=None):
        post = self.get_object()
        if request.method == 'PUT':
            Recommendation.objects.get_or_create(post=post, user=request.user)
        else:
            Recommendation.objects.filter(post=post, user=request.user).delete()
        return Response({
            'id': post.pk,
            'recommendation_count': post.recommendations.count(),
            'is_recommended': post.recommendations.filter(user=request.user).exists(),
        })

    @action(detail=True, methods=['get', 'post'], permission_classes=[IsAuthenticatedOrReadOnly])
    def comments(self, request, pk=None):
        post = self.get_object()
        if request.method == 'GET':
            comments = post.comments.select_related('author').all()
            serializer = CommentSerializer(comments, many=True, context={'request': request})
            return Response(serializer.data)
        serializer = CommentSerializer(data=request.data, context={'request': request})
        serializer.is_valid(raise_exception=True)
        serializer.save(post=post, author=request.user)
        return Response({**serializer.data, 'comment_count': post.comments.count()},
                        status=status.HTTP_201_CREATED)

    @action(detail=True, methods=['delete'], url_path=r'comments/(?P<comment_pk>\d+)',
            permission_classes=[IsAuthenticated])
    def comment_detail(self, request, pk=None, comment_pk=None):
        post = self.get_object()
        comment = get_object_or_404(Comment, pk=comment_pk, post=post)
        if comment.author_id != request.user.pk:
            raise APIPermissionDenied('본인이 작성한 댓글만 삭제할 수 있습니다.')
        comment.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)
