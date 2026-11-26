cmake_minimum_required(VERSION 3.22.1)
project("alsaplay" C)

add_executable(alsaplay alsaplay.c)

set_target_properties(alsaplay PROPERTIES
    OUTPUT_NAME "alsaplay"
    PREFIX "lib"
    SUFFIX ".so"
)

target_compile_options(alsaplay PRIVATE -O2 -Wall -fPIE)
target_link_options(alsaplay PRIVATE -pie)
target_link_libraries(alsaplay dl)
