# common.mk
# Defines common shell utilites for wfl

# Set the shell used on all platforms
SHELL       := bash -o pipefail -c

# Common shell programs
AWK     := awk
CAT     := cat
CD      := cd
CLOJURE := clojure
CP      := cp -f
DATE    := date
DOCKER  := docker
DIRNAME := dirname
ECHO    := echo
EXPORT  := export
FIND    := find
GIT     := git
GREP    := grep
JAVA    := java
JQ      := jq
LN      := ln -f -s
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
UNIQUE  := uniq

# Extensions
CSS       := css
CLJ       := clj
HTML      := html
JS        := js
TIMESTAMP := timestamp
TS        := ts
VUE       := vue

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
