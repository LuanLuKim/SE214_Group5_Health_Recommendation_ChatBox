from django.urls import path

from . import views

urlpatterns = [
    # UC-05: weekly/monthly trends of the caller's own metrics (user id comes from Spring Boot's session).
    path("metrics/trends", views.metric_trends),
    # UC-19: system statistics for administrators, aggregate counts only.
    path("system/statistics", views.system_statistics),
]
