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

# Shamelessly plagiarised from
# Mecklenburg, R., Managing Projects with GNU Make, 3rd Edition.
# $(call brief-help, makefile)
define brief-help
  @$(MAKE) -f $1 --print-data-base --question no-such-target | \
    $(GREP) -v -e '^no-such-target' |                          \
    $(AWK) '/^[^.%][-A-Za-z0-9_]*:/                            \
        { print substr($$1, 1, length($$1)-1) }' |             \
	$(SORT) |                                                  \
    $(PR) --omit-pagination --width=80 --columns=4
endef
