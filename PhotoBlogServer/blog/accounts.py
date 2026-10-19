"""Account APIs: validation, password hashing, token identity and revocation."""
from django.contrib.auth import get_user_model
from django.contrib.auth.password_validation import validate_password
from django.core.exceptions import ValidationError as DjangoValidationError
from django.db import IntegrityError, transaction
from rest_framework import generics, serializers, status
from rest_framework.authentication import TokenAuthentication
from rest_framework.authtoken.views import ObtainAuthToken
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

User = get_user_model()


class RegisterSerializer(serializers.ModelSerializer):
    password = serializers.CharField(write_only=True, trim_whitespace=False, max_length=128)
    password_confirm = serializers.CharField(write_only=True, trim_whitespace=False, max_length=128)

    class Meta:
        model = User
        fields = ('id', 'username', 'password', 'password_confirm')
        read_only_fields = ('id',)

    def validate_username(self, value):
        value = User.normalize_username(value)
        if User.objects.filter(username=value).exists():
            raise serializers.ValidationError('이미 사용 중인 사용자 이름입니다.')
        return value

    def validate(self, attrs):
        if attrs['password'] != attrs['password_confirm']:
            raise serializers.ValidationError({'password_confirm': '비밀번호가 일치하지 않습니다.'})
        try:
            validate_password(attrs['password'], user=User(username=attrs['username']))
        except DjangoValidationError as error:
            raise serializers.ValidationError({'password': error.messages}) from error
        return attrs

    def create(self, validated_data):
        validated_data.pop('password_confirm')
        # A second request can race the uniqueness check. The DB constraint is the final guard.
        try:
            with transaction.atomic():
                return User.objects.create_user(**validated_data)
        except IntegrityError as error:
            raise serializers.ValidationError({'username': '이미 사용 중인 사용자 이름입니다.'}) from error


class RegisterView(generics.CreateAPIView):
    authentication_classes = []
    permission_classes = [AllowAny]
    serializer_class = RegisterSerializer
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = 'signup'


class LoginView(ObtainAuthToken):
    authentication_classes = []
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = 'login'


class CurrentUserView(APIView):
    authentication_classes = [TokenAuthentication]
    permission_classes = [IsAuthenticated]

    def get(self, request):
        return Response({'id': request.user.pk, 'username': request.user.username})


class LogoutView(APIView):
    authentication_classes = [TokenAuthentication]
    permission_classes = [IsAuthenticated]

    def post(self, request):
        # DRF's basic Token model uses one token per user: revokes other devices too.
        request.auth.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)
