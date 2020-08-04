# common.mk
# defines common shell utilites for wfl

ARCH := $(shell uname -s)
include $(MAKE_INCLUDE_DIR)/$(ARCH)/common.mk

# Extensions
CSS  := css
CLJ  := clj
HTML := html
JS   := js
TS   := ts
VUE  := vue
