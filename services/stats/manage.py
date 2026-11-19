#!/usr/bin/env python
"""Django command line for the statistics service. Run: python manage.py runserver 127.0.0.1:8002"""

import os
import sys


def main():
    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "stats_service.settings")
    from django.core.management import execute_from_command_line

    execute_from_command_line(sys.argv)


if __name__ == "__main__":
    main()
