# Instaloader wheel 来源

- 包：`instaloader-4.15.2-py3-none-any.whl`
- Fork：`https://github.com/hyperplural/instaloader`
- Commit：`b1d233362e335cbbccba5c5e4b614a1032764118`
- SHA-256：`fc3af6733a776d9bfcf20c24ef77aa3383d774e790ceb99080340d298199cd7c`
- 生成命令：

  ```bash
  pip3 wheel --no-deps --wheel-dir client/android/python/wheels \
    https://github.com/hyperplural/instaloader/archive/b1d233362e335cbbccba5c5e4b614a1032764118.tar.gz
  ```

wheel 更新时必须固定新 commit、更新本文件校验值，并重新运行 Python fixture 和 Android APK 构建。

Chaquopy 构建同时精确锁定以下纯 Python 运行依赖，禁止只固定 `requests` 而让传递依赖随构建时间漂移：

- `requests==2.34.2`
- `charset-normalizer==3.4.9`
- `idna==3.18`
- `urllib3==2.7.0`
- `certifi==2026.7.22`
