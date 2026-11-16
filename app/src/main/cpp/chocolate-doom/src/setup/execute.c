//
// Copyright(C) 2005-2014 Simon Howard
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 2
// of the License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//

// Code for invoking Doom.
//
// CookieDoom (Android) version: Android cannot fork()/exec() another
// program, so "running the game" means asking the Java side
// (SetupActivity.launchGame) to start the game activity. The game's command
// line is collected exactly like upstream (one parameter per line in a
// response file), then split into arguments and handed over through JNI.

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

#include <sys/types.h>

#include "SDL.h"

#include "textscreen.h"

#include "config.h"
#include "execute.h"
#include "mode.h"
#include "m_argv.h"
#include "m_config.h"
#include "m_misc.h"

// Implemented in android_bridge.c
int Android_LaunchGame(const char *game, int argc, char **argv);

struct execute_context_s
{
    char *response_file;
    FILE *stream;
};

// Returns the path to a temporary file of the given name, stored in the
// app's private storage.

static char *TempFile(const char *s)
{
    const char *tempdir;

    tempdir = SDL_AndroidGetInternalStoragePath();

    if (tempdir == NULL)
    {
        tempdir = ".";
    }

    return M_StringJoin(tempdir, DIR_SEPARATOR_S, s, NULL);
}

static int ArgumentNeedsEscape(const char *arg)
{
    const char *p;

    for (p = arg; *p != '\0'; ++p)
    {
        if (isspace((unsigned char) *p))
        {
            return 1;
        }
    }

    return 0;
}

// Arguments passed to the setup tool should be passed through to the
// game when launching a game.  Calling this adds all arguments from
// myargv to the output context.

void PassThroughArguments(execute_context_t *context)
{
    int i;

    for (i = 1; i < myargc; ++i)
    {
        // "-game <name>" / "-confdir <dir>" only configure Setup itself.
        if ((!strcmp(myargv[i], "-game") || !strcmp(myargv[i], "-confdir"))
         && i + 1 < myargc)
        {
            ++i;
            continue;
        }

        if (ArgumentNeedsEscape(myargv[i]))
        {
            AddCmdLineParameter(context, "\"%s\"", myargv[i]);
        }
        else
        {
            AddCmdLineParameter(context, "%s", myargv[i]);
        }
    }
}

execute_context_t *NewExecuteContext(void)
{
    execute_context_t *result;

    result = malloc(sizeof(execute_context_t));

    result->response_file = TempFile("chocolat.rsp");
    result->stream = M_fopen(result->response_file, "w");

    if (result->stream == NULL)
    {
        fprintf(stderr, "Error opening response file\n");
        exit(-1);
    }

    return result;
}

void AddCmdLineParameter(execute_context_t *context, const char *s, ...)
{
    va_list args;

    va_start(args, s);

    vfprintf(context->stream, s, args);
    fprintf(context->stream, "\n");

    va_end(args);
}

// There is no file manager to open on Android.

boolean OpenFolder(const char *path)
{
    (void) path;
    return false;
}

// Split the response file into arguments. Whitespace separates arguments,
// double quotes group words (and are removed), like a normal shell.

static char **ParseResponseFile(const char *filename, int *argc_out)
{
    FILE *f;
    long size;
    char *data;
    char **argv;
    int argc;
    char *p, *out;
    int in_quote;

    *argc_out = 0;

    f = M_fopen(filename, "rb");
    if (f == NULL)
    {
        return NULL;
    }

    fseek(f, 0, SEEK_END);
    size = ftell(f);
    fseek(f, 0, SEEK_SET);

    data = malloc(size + 1);
    if (data == NULL || fread(data, 1, size, f) != (size_t) size)
    {
        fclose(f);
        free(data);
        return NULL;
    }
    data[size] = '\0';
    fclose(f);

    // Upper bound on the number of arguments: one per byte.
    argv = calloc(size + 2, sizeof(char *));
    argc = 0;

    p = data;
    while (*p != '\0')
    {
        while (*p != '\0' && isspace((unsigned char) *p))
        {
            ++p;
        }
        if (*p == '\0')
        {
            break;
        }

        argv[argc++] = p;
        out = p;
        in_quote = 0;

        while (*p != '\0' && (in_quote || !isspace((unsigned char) *p)))
        {
            if (*p == '"')
            {
                in_quote = !in_quote;
                ++p;
            }
            else
            {
                *out++ = *p++;
            }
        }

        if (*p != '\0')
        {
            ++p;
        }
        *out = '\0';
    }

    // 'data' stays allocated: the strings point into it.
    *argc_out = argc;
    return argv;
}

int ExecuteDoom(execute_context_t *context)
{
    char **argv;
    int argc;
    int result;

    fclose(context->stream);

    argv = ParseResponseFile(context->response_file, &argc);

    result = Android_LaunchGame(GetExecutableName(), argc, argv);

    // Destroy context
    M_remove(context->response_file);
    free(context->response_file);
    free(context);

    return result;
}

// The "Test" button runs the game with -testcontrols and waits for it to
// finish. The game runs as a separate Android activity here, so the button
// is simply not offered.

txt_window_action_t *TestConfigAction(void)
{
    return NULL;
}
