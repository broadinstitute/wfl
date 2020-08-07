# common.mk
# Defines common shell utilites for wfl

# Common shell programs
AWK     := awk
BOOT    := boot
CAT     := cat
CD      := cd
CLOJURE := clojure
CP      := cp -f
DATE    := date
DOCKER  := docker
ECHO    := echo
EXPORT  := export
FIND    := find
GIT     := git
GREP    := grep
JAVA    := java
JQ      := jq
LEIN    := lein
LN      := ln -s
MKDIR   := mkdir -p
MV      := mv -f
NPM     := npm
POPD    := popd
PUSHD   := pushd
PYTHON  := python3
PR      := pr
RMDIR   := rmdir -p
SED     := sed
SOURCE  := .
SORT    := sort
TEE     := tee
TOUCH   := touch
TR      := tr
UNAME   := uname

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
