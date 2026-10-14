# Build libezglibc.so (glibc LD_PRELOAD exec/path layer)
# libezglibc.so(glibc용 LD_PRELOAD 실행·경로 보정 라이브러리) 빌드
#
#   make                                   host build (tests) / 호스트 빌드(시험용)
#   make CC=aarch64-linux-gnu-gcc          device build / 기기용 빌드
#   make test                              host self-test / 호스트 자체 시험

CC      ?= cc
CFLAGS  ?= -O2
CFLAGS  += -std=gnu11 -fPIC -fno-delete-null-pointer-checks -Wno-nonnull-compare -fvisibility=default -Wall -Wextra -Wno-unused-parameter
LDFLAGS += -shared -Wl,-z,nodelete -Wl,--no-undefined
LDLIBS  += -ldl -lpthread

SRCS = ezg_common.c ezg_exec.c ezg_fs.c
OBJS = $(SRCS:.c=.o)
OUT  = libezglibc.so

all: $(OUT)

$(OUT): $(OBJS)
	$(CC) $(LDFLAGS) -o $@ $(OBJS) $(LDLIBS)

%.o: %.c ezg.h
	$(CC) $(CFLAGS) -c -o $@ $<

# Host self-test (x86_64 or aarch64 glibc host) / 호스트 자체 시험
test: $(OUT)
	sh tests/run-host-tests.sh $(abspath $(OUT))

clean:
	rm -f $(OBJS) $(OUT)

.PHONY: all test clean
