#!/bin/bash
export PATH=/home/runner/flutter/bin:$PATH
cd /home/runner/workspace

echo "Building Flutter web app..."
flutter build web --release 2>&1

echo "Starting server on port 5000..."
python3 serve.py
