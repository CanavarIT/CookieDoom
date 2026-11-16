/*
 * CookieDoom - Android glue compiled into every game library.
 *
 *  - Android_RedirectStdio(): pipes stdout/stderr into logcat (tag "CookieDoom").
 *  - DoomActivity.nativeMenuActive(): lets the Java touch overlay know whether
 *    the game menu is open (overlay then acts as a menu pad).
 *
 * The game flavour is chosen at compile time via CD_GAME_* (see CMakeLists).
 */
#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "doomtype.h"

#if defined(CD_GAME_DOOM) || defined(CD_GAME_STRIFE)
extern boolean menuactive;
#define CD_MENU_FLAG menuactive
#elif defined(CD_GAME_HERETIC) || defined(CD_GAME_HEXEN)
extern boolean MenuActive;
extern boolean askforquit;
/* Heretic/Hexen clear MenuActive while a Y/N prompt (quit, end game ...) is
 * shown, so the prompt flag must count as "menu" too - otherwise the touch
 * overlay stays in game mode and the Y / N buttons never appear. */
#define CD_MENU_FLAG (MenuActive || askforquit)
#elif defined(CD_GAME_SETUP)
/* Chocolate Setup: no in-game menu, no save-name entry. */
#define CD_MENU_FLAG 0
#else
#error "Define one of CD_GAME_DOOM / HERETIC / HEXEN / STRIFE / SETUP"
#endif

#ifdef CD_GAME_SETUP
#include "SDL.h"
const char *CD_SaveEditText(void) { return NULL; }
#else
extern const char *CD_SaveEditText(void);
#endif

#define LOG_TAG "CookieDoom"

static int log_pipe[2];

static void *LogThread(void *arg)
{
    char buf[1024];
    char line[1024];
    size_t len = 0;
    ssize_t n;

    (void) arg;

    while ((n = read(log_pipe[0], buf, sizeof(buf))) > 0)
    {
        for (ssize_t i = 0; i < n; ++i)
        {
            if (buf[i] == '\n' || len == sizeof(line) - 1)
            {
                line[len] = '\0';
                __android_log_write(ANDROID_LOG_INFO, LOG_TAG, line);
                len = 0;
                if (buf[i] == '\n')
                {
                    continue;
                }
            }
            line[len++] = buf[i];
        }
    }
    return NULL;
}

void Android_RedirectStdio(void)
{
    pthread_t thread;

    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    if (pipe(log_pipe) != 0)
    {
        return;
    }

    dup2(log_pipe[1], STDOUT_FILENO);
    dup2(log_pipe[1], STDERR_FILENO);

    if (pthread_create(&thread, NULL, LogThread, NULL) == 0)
    {
        pthread_detach(thread);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_cookie_doom_DoomActivity_nativeMenuActive(JNIEnv *env, jclass cls)
{
    (void) env;
    (void) cls;
    return CD_MENU_FLAG ? JNI_TRUE : JNI_FALSE;
}

/* Returns the save-name being edited, or null when no name entry is active. */
JNIEXPORT jstring JNICALL
Java_com_cookie_doom_DoomActivity_nativeSaveText(JNIEnv *env, jclass cls)
{
    const char *t;

    (void) cls;
    t = CD_SaveEditText();
    return t ? (*env)->NewStringUTF(env, t) : NULL;
}

#ifdef CD_GAME_SETUP
/*
 * Called by setup/execute.c instead of fork()+exec(): asks SetupActivity
 * (Java) to start the game activity with the given command line.
 * "exe" is the upstream executable name (e.g. "chocolate-heretic").
 */
int Android_LaunchGame(const char *exe, int argc, char **argv)
{
    JNIEnv *env = (JNIEnv *) SDL_AndroidGetJNIEnv();
    jobject activity = (jobject) SDL_AndroidGetActivity();
    const char *game = "doom";
    jclass cls, strcls;
    jmethodID mid;
    jobjectArray arr;
    jstring jgame;
    int i;

    if (env == NULL || activity == NULL)
    {
        return -1;
    }

    if (exe != NULL)
    {
        if (strstr(exe, "heretic") != NULL) game = "heretic";
        else if (strstr(exe, "hexen") != NULL) game = "hexen";
        else if (strstr(exe, "strife") != NULL) game = "strife";
    }

    cls = (*env)->GetObjectClass(env, activity);
    mid = (*env)->GetMethodID(env, cls, "launchGame",
                              "(Ljava/lang/String;[Ljava/lang/String;)V");
    strcls = (*env)->FindClass(env, "java/lang/String");
    if (mid == NULL || strcls == NULL)
    {
        (*env)->ExceptionClear(env);
        return -1;
    }

    arr = (*env)->NewObjectArray(env, argc, strcls, NULL);
    for (i = 0; i < argc; ++i)
    {
        jstring a = (*env)->NewStringUTF(env, argv[i]);
        (*env)->SetObjectArrayElement(env, arr, i, a);
        (*env)->DeleteLocalRef(env, a);
    }
    jgame = (*env)->NewStringUTF(env, game);

    (*env)->CallVoidMethod(env, activity, mid, jgame, arr);
    if ((*env)->ExceptionCheck(env))
    {
        (*env)->ExceptionClear(env);
        return -1;
    }
    return 0;
}
#endif
