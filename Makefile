#!/usr/bin/make -f
# Project-Level Makefile for WFL

export PROJECT_DIR	    := $(CURDIR)
export DERIVED_DIR		:= $(PROJECT_DIR)/derived
export MAKE_INCLUDE_DIR := $(PROJECT_DIR)/makerules

include $(MAKE_INCLUDE_DIR)/common.mk

export WFL_VERSION := $(shell $(CAT) $(PROJECT_DIR)/version)

MODULES	    := api ui cloud_function

.PHONY: all $(MODULES) clean
all: $(MODULES)

$(MODULES):
	@+$(CD) $@ && $(MAKE) -f module.mk MODULE=$@ $(TARGET)

.PHONY: clean
clean:
	$(RM) -r $(DERIVED_DIR)
