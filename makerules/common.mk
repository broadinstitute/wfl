# common.mk
# defines common shell utilites for wfl

ARCH := $(shell uname -s)
include $(MAKE_INCLUDE_DIR)/$(ARCH)/common.mk

# Extensions
CLJ := clj
TS  := ts
