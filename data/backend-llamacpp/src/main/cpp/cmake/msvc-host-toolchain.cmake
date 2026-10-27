# Host toolchain for llama.cpp's vulkan-shaders-gen on a Windows build machine.
#
# The Vulkan backend compiles a small generator for the build machine itself,
# which then runs glslc over every shader. llama.cpp looks for cl/gcc/clang on
# PATH, but Android Studio and Gradle run without a Visual Studio environment,
# so cl is not on PATH and could not find its headers if it were. This file
# locates the newest MSVC and Windows SDK with vswhere and sets them up
# explicitly, so no developer shell is needed.
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_BUILD_TYPE Release)

set(_vswhere "$ENV{ProgramFiles\(x86\)}/Microsoft Visual Studio/Installer/vswhere.exe")
if(NOT EXISTS "${_vswhere}")
    message(FATAL_ERROR "vswhere.exe not found; install Visual Studio Build Tools with the C++ workload.")
endif()
execute_process(
    COMMAND "${_vswhere}" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64
            -property installationPath
    OUTPUT_VARIABLE _vs OUTPUT_STRIP_TRAILING_WHITESPACE)
if(_vs STREQUAL "")
    message(FATAL_ERROR "No Visual Studio installation with the x64 C++ tools was found.")
endif()
file(TO_CMAKE_PATH "${_vs}" _vs)

file(GLOB _msvc_versions LIST_DIRECTORIES true "${_vs}/VC/Tools/MSVC/*")
list(SORT _msvc_versions COMPARE NATURAL ORDER DESCENDING)
list(GET _msvc_versions 0 _msvc)

set(_kits "$ENV{ProgramFiles\(x86\)}/Windows Kits/10")
file(TO_CMAKE_PATH "${_kits}" _kits)
file(GLOB _sdk_versions LIST_DIRECTORIES true "${_kits}/Include/10.*")
list(SORT _sdk_versions COMPARE NATURAL ORDER DESCENDING)
list(GET _sdk_versions 0 _sdk_include)
get_filename_component(_sdk "${_sdk_include}" NAME)

set(CMAKE_C_COMPILER "${_msvc}/bin/Hostx64/x64/cl.exe")
set(CMAKE_CXX_COMPILER "${_msvc}/bin/Hostx64/x64/cl.exe")
set(CMAKE_LINKER "${_msvc}/bin/Hostx64/x64/link.exe")
set(CMAKE_RC_COMPILER "${_kits}/bin/${_sdk}/x64/rc.exe")
set(CMAKE_MT "${_kits}/bin/${_sdk}/x64/mt.exe")

set(_includes
    "${_msvc}/include"
    "${_kits}/Include/${_sdk}/ucrt"
    "${_kits}/Include/${_sdk}/um"
    "${_kits}/Include/${_sdk}/shared")
set(_libs
    "${_msvc}/lib/x64"
    "${_kits}/Lib/${_sdk}/ucrt/x64"
    "${_kits}/Lib/${_sdk}/um/x64")

set(CMAKE_C_STANDARD_INCLUDE_DIRECTORIES ${_includes})
set(CMAKE_CXX_STANDARD_INCLUDE_DIRECTORIES ${_includes})
set(CMAKE_RC_STANDARD_INCLUDE_DIRECTORIES ${_includes})
foreach(_lib IN LISTS _libs)
    string(APPEND _libpath " /LIBPATH:\"${_lib}\"")
endforeach()
set(CMAKE_EXE_LINKER_FLAGS_INIT "${_libpath}")

set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)
