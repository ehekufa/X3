package com.x3.screenrec;

import android.content.Context;

/**
 * Загрузка id ресурсов по имени.
 *
 * Зачем: сборка без Gradle (aapt в build-tools 34) не всегда генерирует
 * R.java, поэтому код не ссылается на класс R, а берёт id по имени.
 * В Gradle/Android Studio работает так же.
 */
final class Res {

    private Res() {
    }

    static int of(Context context, String name, String type) {
        int id = context.getResources().getIdentifier(name, type, context.getPackageName());
        if (id == 0) {
            throw new IllegalStateException("Ресурс не найден: " + type + "/" + name);
        }
        return id;
    }
}
