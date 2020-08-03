#!/usr/bin/make -f
# Project-Level Makefile for WFL

PROJECT_DIR	:= $(PWD)
DERIVED_DIR	:= $(PROJECT_DIR)/derived
MODULES	    := api

.PHONY: all $(MODULES) clean
all: $(MODULES)

$(MODULES):
	@+$(MAKE) --directory=$@ $(TARGET)

.PHONY: clean
clean:
	@echo $(RM) -r $(DERIVED_DIR)
