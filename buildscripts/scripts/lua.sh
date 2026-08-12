#!/bin/bash -e

. ../../include/depinfo.sh
. ../../include/path.sh

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	make clean
	exit 0
else
	exit 255
fi

# Building seperately from source tree is not supported, this means we are forced to always clean
$0 clean

# PLAT=linux implies LUA_USE_POSIX, which points liolib's seek macros at
# fseeko/ftello. bionic only declares those for 32-bit from API 24, so an
# armeabi-v7a build below that fails to compile liolib.c outright. Defining
# lua_fseek (the key of liolib.c's "#if !defined(lua_fseek) && !defined(LUA_ANSI)"
# guard, and used nowhere else) skips that block, and liolib's own ISO C
# fallback below it supplies fseek/ftell/long instead. Long offsets are
# irrelevant to mpv's scripts. Only applied below API 24, so the regular build
# keeps large-file seeks.
lua_seek_flags=""
if [ "${MPV_API_LEVEL:-26}" -lt 24 ]; then
	lua_seek_flags="-Dlua_fseek=1"
fi

# LUA_T= and LUAC_T= to disable building lua & luac
# -Dgetlocaledecpoint()=('.') fixes bionic missing decimal_point in localeconv
make CC="$CC" AR="$AR rc" RANLIB="$RANLIB" \
	MYCFLAGS="-fPIC -Dgetlocaledecpoint\(\)=\(\'.\'\) $lua_seek_flags" \
	PLAT=linux LUA_T= LUAC_T= -j$cores

# TO_BIN=/dev/null disables installing lua & luac
make INSTALL=${INSTALL:-install} INSTALL_TOP="$prefix_dir" TO_BIN=/dev/null install

# make pc only generates a partial pkg-config file because ????
mkdir -p $prefix_dir/lib/pkgconfig
make pc >$prefix_dir/lib/pkgconfig/lua.pc
cat >>$prefix_dir/lib/pkgconfig/lua.pc <<'EOF'
Name: Lua
Description:
Version: ${version}
Libs: -L${libdir} -llua
Cflags: -I${includedir}
EOF
