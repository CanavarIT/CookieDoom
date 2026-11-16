cmake_minimum_required(VERSION 3.22)
project(CookieDoom C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(CHOC_DIR  ${CMAKE_CURRENT_SOURCE_DIR}/chocolate-doom)
set(DEPS_DIR  ${CMAKE_CURRENT_SOURCE_DIR}/deps)

if(NOT EXISTS ${DEPS_DIR}/SDL2/CMakeLists.txt OR NOT EXISTS ${DEPS_DIR}/SDL2_mixer/CMakeLists.txt
   OR NOT EXISTS ${DEPS_DIR}/SDL2_net/CMakeLists.txt)
    message(FATAL_ERROR "SDL2 / SDL2_mixer / SDL2_net not found in ${DEPS_DIR}. Run:  ./gradlew fetchDeps")
endif()

# ---------------------------------------------------------------- SDL2 ----
set(SDL_SHARED ON  CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST   OFF CACHE BOOL "" FORCE)
set(SDL_TESTS  OFF CACHE BOOL "" FORCE)
add_subdirectory(${DEPS_DIR}/SDL2 SDL2)

# -------------------------------------------------- SDL2_mixer (shared) ---
# Doom only needs WAV sfx plus the OPL hook for music; keep it tiny.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(SDL2MIXER_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(SDL2MIXER_VENDORED     OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_DEPS_SHARED  OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_INSTALL      OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_SAMPLES      OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_CMD          OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_FLAC         OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_GME          OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_MOD          OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_MIDI         OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_MP3          OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_OPUS         OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_WAVPACK      OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_WAVE         ON  CACHE BOOL "" FORCE)
set(SDL2MIXER_VORBIS       STB CACHE STRING "" FORCE)
add_subdirectory(${DEPS_DIR}/SDL2_mixer SDL2_mixer)

# Target names differ slightly between SDL releases - resolve what exists.
foreach(t SDL2::SDL2 SDL2)
    if(TARGET ${t} AND NOT CHOC_SDL)
        set(CHOC_SDL ${t})
    endif()
endforeach()
foreach(t SDL2_mixer::SDL2_mixer SDL2_mixer SDL2_mixer::SDL2_mixer-static SDL2_mixer-static)
    if(TARGET ${t} AND NOT CHOC_MIXER)
        set(CHOC_MIXER ${t})
    endif()
endforeach()
if(NOT CHOC_SDL OR NOT CHOC_MIXER)
    message(FATAL_ERROR "SDL2 / SDL2_mixer targets not found (sdl='${CHOC_SDL}' mixer='${CHOC_MIXER}')")
endif()

# ------------------------------------------------------ Chocolate Doom ----
include(sources.cmake)

# ----------------------------------------------- SDL2_net (static) --------
# Networking for multiplayer. Built straight from its sources (no need for its
# own CMake project): SDLnet*.c are the library, everything else is demos.
file(GLOB CHOC_NET_SOURCES ${DEPS_DIR}/SDL2_net/SDLnet*.c ${DEPS_DIR}/SDL2_net/src/SDLnet*.c)
if(NOT CHOC_NET_SOURCES)
    message(FATAL_ERROR "No SDLnet*.c sources found in ${DEPS_DIR}/SDL2_net")
endif()
add_library(choc_sdlnet STATIC ${CHOC_NET_SOURCES})
target_include_directories(choc_sdlnet PUBLIC ${DEPS_DIR}/SDL2_net)
target_link_libraries(choc_sdlnet PUBLIC ${CHOC_SDL})

set(CHOC_INCLUDES
    ${DEPS_DIR}/SDL2_net
    ${CMAKE_CURRENT_SOURCE_DIR}          # config.h
    ${CHOC_DIR}/src
    ${CHOC_DIR}/opl
    ${CHOC_DIR}/pcsound
    ${CHOC_DIR}/textscreen)

# Multiplayer uses SDL2_net (see choc_sdlnet above).
set(CHOC_DEFS "")

# Sub-systems shared by every game.
add_library(choc_opl STATIC
    ${CHOC_DIR}/opl/opl.c ${CHOC_DIR}/opl/opl_linux.c ${CHOC_DIR}/opl/opl_obsd.c
    ${CHOC_DIR}/opl/opl_queue.c ${CHOC_DIR}/opl/opl_sdl.c ${CHOC_DIR}/opl/opl_timer.c
    ${CHOC_DIR}/opl/opl_win32.c ${CHOC_DIR}/opl/ioperm_sys.c ${CHOC_DIR}/opl/opl3.c)

add_library(choc_pcsound STATIC
    ${CHOC_DIR}/pcsound/pcsound.c ${CHOC_DIR}/pcsound/pcsound_bsd.c
    ${CHOC_DIR}/pcsound/pcsound_sdl.c ${CHOC_DIR}/pcsound/pcsound_linux.c
    ${CHOC_DIR}/pcsound/pcsound_win32.c)

file(GLOB CHOC_TXT_SOURCES ${CHOC_DIR}/textscreen/txt_*.c)
add_library(choc_textscreen STATIC ${CHOC_TXT_SOURCES})

foreach(lib choc_opl choc_pcsound choc_textscreen)
    target_include_directories(${lib} PRIVATE ${CHOC_INCLUDES})
    target_compile_definitions(${lib} PRIVATE ${CHOC_DEFS})
    target_link_libraries(${lib} PUBLIC ${CHOC_SDL} ${CHOC_MIXER} m)
endforeach()

# Engine code common to all games (compiled once, reused 4x).
add_library(choc_common OBJECT ${CHOC_COMMON_SOURCES})
add_library(choc_deh    OBJECT ${CHOC_DEH_SOURCES})
foreach(lib choc_common choc_deh)
    target_include_directories(${lib} PRIVATE ${CHOC_INCLUDES})
    target_compile_definitions(${lib} PRIVATE ${CHOC_DEFS})
    target_link_libraries(${lib} PRIVATE ${CHOC_SDL} ${CHOC_MIXER})
endforeach()

# One shared library per game. SDLActivity loads  libSDL2.so + lib<game>.so
# and calls SDL_main() (i_main.c's main() is renamed by SDL_main.h).
function(add_choc_game NAME DEFINE USE_DEH)
    string(TOUPPER ${NAME} UPNAME)

    add_library(choc_${NAME}_static STATIC ${CHOC_${UPNAME}_SOURCES})
    target_include_directories(choc_${NAME}_static PRIVATE
        ${CHOC_INCLUDES} ${CHOC_DIR}/src/${NAME})
    target_compile_definitions(choc_${NAME}_static PRIVATE ${CHOC_DEFS})
    target_link_libraries(choc_${NAME}_static PRIVATE ${CHOC_SDL} ${CHOC_MIXER})

    set(OBJS $<TARGET_OBJECTS:choc_common>)
    if(USE_DEH)
        list(APPEND OBJS $<TARGET_OBJECTS:choc_deh>)
    endif()

    add_library(${NAME} SHARED ${OBJS} android_bridge.c)
    target_include_directories(${NAME} PRIVATE ${CHOC_INCLUDES})
    target_compile_definitions(${NAME} PRIVATE ${CHOC_DEFS} ${DEFINE})
    target_link_libraries(${NAME} PRIVATE
        choc_${NAME}_static choc_textscreen choc_pcsound choc_opl choc_sdlnet
        ${CHOC_SDL} ${CHOC_MIXER} m log)
endfunction()

# ------------------------------------------------ Chocolate Setup --------
# libsetup.so: the original text-mode Setup program (configuration and
# multiplayer lobby). It runs in its own activity (SetupActivity); starting a
# game goes through Android_LaunchGame() in android_bridge.c.
set(SETUP_DIR ${CHOC_DIR}/src/setup)
add_library(setup SHARED
    ${CHOC_DIR}/src/i_main.c        ${CHOC_DIR}/src/i_system.c
    ${CHOC_DIR}/src/m_argv.c        ${CHOC_DIR}/src/m_misc.c
    ${CHOC_DIR}/src/deh_str.c       ${CHOC_DIR}/src/d_mode.c
    ${CHOC_DIR}/src/d_iwad.c        ${CHOC_DIR}/src/i_timer.c
    ${CHOC_DIR}/src/m_config.c      ${CHOC_DIR}/src/m_controls.c
    ${CHOC_DIR}/src/net_io.c        ${CHOC_DIR}/src/net_packet.c
    ${CHOC_DIR}/src/net_petname.c   ${CHOC_DIR}/src/net_sdl.c
    ${CHOC_DIR}/src/net_query.c     ${CHOC_DIR}/src/net_structrw.c
    ${CHOC_DIR}/src/z_native.c
    ${SETUP_DIR}/compatibility.c ${SETUP_DIR}/display.c   ${SETUP_DIR}/execute.c
    ${SETUP_DIR}/joystick.c      ${SETUP_DIR}/keyboard.c  ${SETUP_DIR}/mainmenu.c
    ${SETUP_DIR}/mode.c          ${SETUP_DIR}/mouse.c     ${SETUP_DIR}/multiplayer.c
    ${SETUP_DIR}/sound.c         ${SETUP_DIR}/txt_joyaxis.c
    ${SETUP_DIR}/txt_joybinput.c ${SETUP_DIR}/txt_keyinput.c
    ${SETUP_DIR}/txt_mouseinput.c
    android_bridge.c)
target_include_directories(setup PRIVATE ${CHOC_INCLUDES} ${SETUP_DIR})
target_compile_definitions(setup PRIVATE ${CHOC_DEFS} CD_GAME_SETUP)
target_link_libraries(setup PRIVATE
    choc_textscreen choc_sdlnet ${CHOC_SDL} ${CHOC_MIXER} m log)

add_choc_game(doom    CD_GAME_DOOM    TRUE)
add_choc_game(heretic CD_GAME_HERETIC TRUE)
add_choc_game(hexen   CD_GAME_HEXEN   FALSE)
add_choc_game(strife  CD_GAME_STRIFE  TRUE)
