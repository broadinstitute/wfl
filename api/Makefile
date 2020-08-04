# Makefile for the wfl/api module

MODULE                       ?= api
REQUIRED_GITHUB_REPOSITORIES := \
	broadinstitute/dsde-pipelines \
	broadinstitute/pipeline-config

# Functions
BOOT  := boot
ECHO  := echo
FIND  := find
GIT   := git
MKDIR := mkdir -p
PUSHD := pushd
POPD  := popd
TOUCH := touch

# Directories
PROJECT_DIR         ?= $(shell dirname $(CURDIR))
MODULE_DIR          := $(PROJECT_DIR)/$(MODULE)
DERIVED_DIR 	    ?= $(PROJECT_DIR)/derived
THIRD_PARTY_DIR     := $(DERIVED_DIR)/3p
DERIVED_MODULE_DIR  := $(DERIVED_DIR)/$(MODULE)

# Extensions
TS := ts

# Build targets and hooks
BUILD_TARGETS := prebuild build
PREBUILD      := $(DERIVED_MODULE_DIR)/prebuild.$(TS)
BUILD         := $(DERIVED_MODULE_DIR)/build.$(TS)

.phony:	all $(BUILD_TARGETS)
all: $(BUILD_TARGETS) check

$(BUILD_TARGETS) check: | $(DERIVED_MODULE_DIR)
prebuild: $(PREBUILD)
build: $(BUILD)

# Append to CLEAN_DIRS to remove directory and contents on `clean`
# Append to CLEAN_FILES to remove file on `clean`
CLEAN_DIRS  := $(DERIVED_MODULE_DIR)
CLEAN_FILES :=
.PHONY:	clean
clean:
	@$(RM) -r $(CLEAN_DIRS)
	@$(RM) $(CLEAN_FILES)

$(DERIVED_DIR) $(THIRD_PARTY_DIR) $(DERIVED_MODULE_DIR):
	@$(MKDIR) $@

%.$(TS):
	@$(TOUCH) $@

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
CPCACHE_DIR := $(MODULE_DIR)/.cpcache
TARGET_DIR  := $(DERIVED_MODULE_DIR)/target
SRC_DIR	    := $(MODULE_DIR)/src

SCM_SRC := $(shell $(FIND) $(SRC_DIR) -name '*.clj')
WFL.JAR := $(TARGET_DIR)/wfl.jar

RESOURCES.TS := $(DERIVED_MODULE_DIR)/resources/.$(TS)

$(PREBUILD): $(RESOURCES.TS)
$(RESOURCES.TS):
	$(MKDIR) $(@D)
	$(BOOT) prebuild
	$(TOUCH) $@

$(BUILD): $(WFL.JAR)
$(WFL.JAR): $(SCM_SRC)
	@$(MKDIR) $(@D)
	$(BOOT) build
	@$(TOUCH) $@

$(CLEAN_DIRS) += $(CPCACHE_DIR) $(TARGET_DIR)
