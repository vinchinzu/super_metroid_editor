SNES_SPC_DIR = /tmp/snes_spc

CFLAGS = -I$(SNES_SPC_DIR) -O2 -Wall
CXXFLAGS = -I$(SNES_SPC_DIR) -O2 -Wall -std=c++11 -DSPC_ISOLATED_ECHO_BUFFER -DBLARGG_BUILD_DLL
LDFLAGS = $(SNES_SPC_DIR)/libspc.a -lstdc++

all: spc2wav sm_spc_render

spc2wav: spc2wav.c
	$(CC) $(CFLAGS) -o $@ $< $(LDFLAGS)

sm_spc_render: sm_spc_render.cpp
	$(CXX) $(CXXFLAGS) -o $@ $< $(LDFLAGS)

clean:
	rm -f spc2wav sm_spc_render
