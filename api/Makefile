# Makefile for the wfl/api module

MODULE                       ?= api
REQUIRED_GITHUB_REPOSITORIES := \
	broadinstitute/dsde-pipelines \
	broadinstitute/pipeline-config

# Functions
BOOT    := boot
CLOJURE := clojure
ECHO    := echo
FIND    := find
GIT     := git
LN 	    := ln -s
MKDIR   := mkdir -p
PUSHD   := pushd
POPD    := popd
TOUCH   := touch

# Directories
PROJECT_DIR         ?= $(shell dirname $(CURDIR))
MODULE_DIR          := $(PROJECT_DIR)/$(MODULE)
DERIVED_DIR 	    ?= $(PROJECT_DIR)/derived
THIRD_PARTY_DIR     := $(DERIVED_DIR)/3p
DERIVED_MODULE_DIR  := $(DERIVED_DIR)/$(MODULE)

# Extensions
TS  := ts
CLJ := clj

# Build targets and hooks
BUILD_TARGETS := prebuild build
PREBUILD      := $(DERIVED_MODULE_DIR)/prebuild.$(TS)
BUILD         := $(DERIVED_MODULE_DIR)/build.$(TS)
CLEAN_DIRS    := $(DERIVED_MODULE_DIR)
CLEAN_FILES   :=

.phony:	all $(BUILD_TARGETS)
all: $(BUILD_TARGETS) check

$(BUILD_TARGETS) check: | $(DERIVED_MODULE_DIR)
prebuild: $(PREBUILD)
build: $(BUILD)

.PHONY:	clean
clean:
	@$(RM) -r $(CLEAN_DIRS)
	@$(RM) $(CLEAN_FILES)

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
CPCACHE_DIR   := $(MODULE_DIR)/.cpcache
SRC_DIR	      := $(MODULE_DIR)/src

DERIVED_RESOURCES_DIR := $(DERIVED_MODULE_DIR)/resources
DERIVED_TARGET_DIR    := $(DERIVED_MODULE_DIR)/target

CLEAN_DIRS += $(CPCACHE_DIR)

RESOURCES := $(DERIVED_RESOURCES_DIR).$(TS)
$(PREBUILD):
	@$(MKDIR) $(DERIVED_RESOURCES_DIR)
	@$(BOOT) prebuild
	@$(TOUCH) $@

SCM_SRC 	:= $(shell $(FIND) $(SRC_DIR) -name "*.$(CLJ)")
WFL_API.JAR := $(DERIVED_TARGET_DIR)/wfl-api.jar
$(BUILD): $(WFL_API.JAR)
$(WFL_API.JAR): $(SCM_SRC)
	@$(MKDIR) $(DERIVED_TARGET_DIR)
	@$(BOOT) build
	@$(LN) $(@D)/wfl-*.jar $@

check:
	CPCACHE=$(CPCACHE_DIR) $(CLOJURE) $(CLJFLAGS) -A:test unit
