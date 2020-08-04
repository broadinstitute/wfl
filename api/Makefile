# Makefile for the wfl/api module

# Shell Functions
BOOT    := boot
CAT     := cat
CLOJURE := clojure
CP      := cp
DOCKER  := docker
ECHO    := echo
EXPORT  := export
FIND    := find
GIT     := git
JAVA    := java
JQ      := jq
LN 	    := ln -s
MKDIR   := mkdir -p
PUSHD   := pushd
POPD    := popd
SED     := sed
TOUCH   := touch

# Extensions
CLJ := clj
TS  := ts

MODULE                       ?= api
REQUIRED_GITHUB_REPOSITORIES := \
	broadinstitute/dsde-pipelines \
	broadinstitute/pipeline-config

# Directories
PROJECT_DIR         ?= $(shell dirname $(CURDIR))
MODULE_DIR          := $(PROJECT_DIR)/$(MODULE)
DERIVED_DIR 	    ?= $(PROJECT_DIR)/derived
THIRD_PARTY_DIR     := $(DERIVED_DIR)/3p
DERIVED_MODULE_DIR  := $(DERIVED_DIR)/$(MODULE)
export WFL_VERSION	?= $(shell $(CAT) $(PROJECT_DIR)/version)

# Build targets and hooks
BUILD_TARGETS := prebuild build images
PREBUILD      := $(DERIVED_MODULE_DIR)/prebuild.$(TS)
BUILD         := $(DERIVED_MODULE_DIR)/build.$(TS)
IMAGES        := $(DERIVED_MODULE_DIR)/images.$(TS)
CLEAN_DIRS    := $(DERIVED_MODULE_DIR)
CLEAN_FILES   :=

.phony:	all $(BUILD_TARGETS)
all: $(BUILD_TARGETS) check

$(BUILD_TARGETS) check: | $(DERIVED_MODULE_DIR)
prebuild: $(PREBUILD)
build: $(BUILD)
images: $(IMAGES)

.PHONY:	clean
clean:
	$(RM) -r $(CLEAN_DIRS)
	$(RM) $(CLEAN_FILES)

$(DERIVED_DIR) $(THIRD_PARTY_DIR) $(DERIVED_MODULE_DIR):
	@$(MKDIR) $@

%.$(TS):
	@$(TOUCH) $@

# 3p Repositories
THIRD_PARTY_REPOSITORIES := \
  $(patsubst %, $(THIRD_PARTY_DIR)/%.$(TS),$(REQUIRED_GITHUB_REPOSITORIES))

define make-github-url
  $(patsubst $(THIRD_PARTY_DIR)/%.$(TS),git@github.com:%.git,$@)
endef

$(PREBUILD): $(THIRD_PARTY_REPOSITORIES)
$(THIRD_PARTY_REPOSITORIES): $(THIRD_PARTY_DIR)
	$(GIT) clone $(make-github-url) $(patsubst %.$(TS),%,$@)
	@$(TOUCH) $@

# Makefile for API
CPCACHE_DIR   := $(DERIVED_MODULE_DIR)/.cpcache
SRC_DIR	      := $(MODULE_DIR)/src

DERIVED_RESOURCES_DIR := $(DERIVED_MODULE_DIR)/resources
DERIVED_TARGET_DIR    := $(DERIVED_MODULE_DIR)/target

CLEAN_DIRS += $(CPCACHE_DIR)

RESOURCES := $(DERIVED_RESOURCES_DIR).$(TS)
$(PREBUILD):
	@$(MKDIR) $(DERIVED_RESOURCES_DIR)
	$(BOOT) prebuild
	@$(TOUCH) $@

SCM_SRC  := $(shell $(FIND) $(SRC_DIR) -name "*.$(CLJ)")
ARTIFACT := $(DERIVED_TARGET_DIR)/wfl-$(WFL_VERSION).jar
SYMLINK  := $(DERIVED_TARGET_DIR)/wfl.jar

$(BUILD): $(ARTIFACT) $(SYMLINK)
$(ARTIFACT): $(SCM_SRC)
	@$(MKDIR) $(DERIVED_TARGET_DIR)
	$(BOOT) build

$(SYMLINK): $(ARTIFACT)
	$(LN) $< $@

check:
	$(EXPORT) CPCACHE=$(CPCACHE_DIR) && $(CLOJURE) $(CLJFLAGS) -A:test unit

$(MODULE)_IMAGE_NAME := broadinstitute/workflow-launcher-$(MODULE)

$(IMAGES): $(MODULE_DIR)/Dockerfile $(ARTIFACT)
	$(DOCKER) build                                  \
		--file $<                                    \
		--tag $($(MODULE)_IMAGE_NAME):$(WFL_VERSION) \
		$(DERIVED_MODULE_DIR)
	@$(TOUCH) $@
