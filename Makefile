#!/usr/bin/make -f
# Project-Level Makefile for WFL

export PROJECT_DIR      := $(CURDIR)
export DERIVED_DIR      := $(PROJECT_DIR)/derived
export MAKE_INCLUDE_DIR := $(PROJECT_DIR)/makerules

include $(MAKE_INCLUDE_DIR)/common.mk

# Enable for `WFL_VERSION` to be overriden
# Example:
# $ WFL_VERSION=1.2.3 make
export WFL_VERSION ?= $(shell $(CAT) $(PROJECT_DIR)/version)

MODULES := api cloud_function docs helm ui

.PHONY: all $(MODULES) clean
all: $(MODULES)

$(MODULES):
	@+$(CD) $@ && $(MAKE) -f module.mk MODULE=$@ $(TARGET)

.PHONY: help
help:
	@$(call brief-help, $(PROJECT_DIR)/Makefile)

.PHONY: clean
clean:
	for m in $(MODULES);                    \
	do                                      \
		$(PUSHD) $$m;                       \
		$(MAKE) -f module.mk MODULE=$$m $@; \
		$(POPD);                            \
	done
	$(RM) -r $(DERIVED_DIR)
