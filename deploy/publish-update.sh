#!/usr/bin/env bash
# Публикует новую версию приложения: после этого открытое приложение само покажет кнопку «Обновить».
# Использование (на сервере, от root):
#   bash publish-update.sh /путь/к/app-release.apk КОД_ВЕРСИИ "1.12" "Что нового"
# КОД_ВЕРСИИ — число больше предыдущего. В GitHub Actions это номер запуска (run number).
set -euo pipefail
APK="${1:?укажите путь к APK}"
CODE="${2:?укажите versionCode (число)}"
NAME="${3:?укажите versionName, например 1.12}"
NOTES="${4:-}"
DIR=/opt/messenger/server/public/downloads

[[ "$CODE" =~ ^[0-9]+$ ]] || { echo "versionCode должен быть числом"; exit 1; }
[[ -f "$APK" ]] || { echo "Файл не найден: $APK"; exit 1; }
[[ "$(head -c 2 "$APK")" == "PK" ]] || { echo "Это не APK-файл"; exit 1; }

CUR=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["versionCode"])' "$DIR/version.json" 2>/dev/null || echo 0)
if [ "$CODE" -le "$CUR" ]; then
  echo "versionCode $CODE должен быть больше текущего ($CUR), иначе телефоны не предложат обновление"; exit 1
fi

# Сначала копируем APK под временным именем, потом переименовываем: скачивание не получит половину файла
install -m 644 "$APK" "$DIR/messenger.apk.tmp"
mv "$DIR/messenger.apk.tmp" "$DIR/messenger.apk"

python3 - "$DIR/version.json" "$CODE" "$NAME" "$NOTES" <<'PY'
import json, sys
path, code, name, notes = sys.argv[1:5]
data = {"versionCode": int(code), "versionName": name, "url": "/download/messenger.apk", "notes": notes}
tmp = path + ".tmp"
with open(tmp, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False)
import os; os.replace(tmp, path)
PY

chown messenger:messenger "$DIR/messenger.apk" "$DIR/version.json"
echo "Опубликовано: версия $NAME (код $CODE). Приложения увидят обновление при следующем открытии."
