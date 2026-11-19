from django.urls import include, path

from stats_api import views

urlpatterns = [
    path("healthz", views.healthz),
    path("v1/", include("stats_api.urls")),
]

handler404 = "stats_api.views.not_found"
handler500 = "stats_api.views.server_error"
