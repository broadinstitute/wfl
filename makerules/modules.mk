# Module targets

ifndef MODULE
$(error MODULE was not defined - please invoke make from top level)
endif

include $(MAKE_INCLUDE_DIR)/common.mk

# Directories
PROJECT_DIR        ?= $(shell dirname $(CURDIR))
MODULE_DIR         := $(PROJECT_DIR)/$(MODULE)
DERIVED_DIR        ?= $(PROJECT_DIR)/derived
DERIVED_MODULE_DIR := $(DERIVED_DIR)/$(MODULE)
export WFL_VERSION ?= $(shell $(CAT) $(PROJECT_DIR)/version)

# Top level `make` targets for the module
MAKE_TARGETS := prebuild lint build unit integration check images

# Timestamps for the top level make targets in a loose order of their timeings.
# Implementers should write module make-targets against these, ensuring that
# the time stamp is touched as the last action.
# Example:
# $(LINT): $(SCM_SRC) $(TEST_SCM_SRC)
# 	$(PYTHON) -m pylint $(PYLINT_OPTIONS)
# 	@$(TOUCH) $@

PREBUILD    := $(DERIVED_MODULE_DIR)/prebuild.$(TIMESTAMP)
LINT        := $(DERIVED_MODULE_DIR)/lint.$(TIMESTAMP)    
BUILD       := $(DERIVED_MODULE_DIR)/build.$(TIMESTAMP)   
UNIT        := $(DERIVED_MODULE_DIR)/unit.$(TIMESTAMP)  
INTEGRATION := $(DERIVED_MODULE_DIR)/integration.$(TIMESTAMP)
IMAGES      := $(DERIVED_MODULE_DIR)/images.$(TIMESTAMP)

.PHONY:	all $(MAKE_TARGETS)
all: $(MAKE_TARGETS)

# Configure `make` dependencies via their timestamp.
$(PREBUILD):    $(shell eval $(MKDIR) $(DERIVED_MODULE_DIR))
$(LINT):        $(PREBUILD)
$(BUILD):       $(LINT)
$(UNIT):        $(BUILD)
$(INTEGRATION): $(BUILD)
$(IMAGES):      $(BUILD)

check: unit integration

# Top level `make` targets depend on their corresponding time stamp.
$(MAKE_TARGETS): % : $(DERIVED_MODULE_DIR)/%.$(TIMESTAMP)
	@$(ECHO) $(MODULE) $@ finished on $(shell $(DATE))

# Use a pattern instead of the actual timestamp to allow module.mk
# implementers to override the default recipe.
$(DERIVED_MODULE_DIR)/%.$(TIMESTAMP):
	@$(TOUCH) $@

# CLEAN is a PHONY target for custom clean recipes
CLEAN       := $(MODULE).clean
CLEAN_FILES :=
CLEAN_DIRS  := $(DERIVED_MODULE_DIR)

.PHONY:	clean $(CLEAN)
$(CLEAN):
clean: $(CLEAN)
	$(RM) $(CLEAN_FILES)
	$(RM) -r $(CLEAN_DIRS)

DISTCLEAN_FILES :=
DISTCLEAN_DIRS  :=

.PHONY: distclean
distclean: clean
	$(RM) $(DISTCLEAN_FILES)
	$(RM) -r $(DISTCLEAN_DIRS)

.PHONY: help
help:
	@$(call brief-help, $(MODULE_DIR)/module.mk)

$(DERIVED_DIR) $(DERIVED_MODULE_DIR):
	@$(MKDIR) $@

ifneq ($(REQUIRED_PYTHON_ENVIRONMENT),)
  include $(MAKE_INCLUDE_DIR)/Python.mk
endif
