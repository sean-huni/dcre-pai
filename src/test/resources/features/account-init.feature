@pai
Feature: PAI creditor account init for the ENDO flow

  Before the ENDO fork, PAI walks every spine transaction of an arrival:
  a known creditor account gets an EXISTS verdict; an unknown one is minted
  idempotently (R-11) and gets a CREATED verdict. Verdicts are immutable, so
  a rerun never flips CREATED to EXISTS and never mints duplicates (R-05).
  An arrival where every account already exists is a valid no-op (A-7).
  ENDO is the Revolving Facility product stream (client FNBRF01, R-41); its
  unknown creditor accounts are registered on the fly with reasonable defaults.

  Scenario: Entries referencing existing accounts receive EXISTS verdicts
    Given creditor accounts "A, B" already exist on file
    And an arrival with entries crediting accounts "A, B"
    When the PAI job runs for the arrival
    Then the PAI job completes
    And the arrival has exactly 2 "EXISTS" and 0 "CREATED" verdicts
    And no new accounts were minted

  Scenario: Unknown creditor accounts are minted with CREATED verdicts
    Given an arrival with entries crediting unknown accounts "X, Y"
    When the PAI job runs for the arrival
    Then the PAI job completes
    And the arrival has exactly 0 "EXISTS" and 2 "CREATED" verdicts
    And exactly one account row exists for each of "X, Y"

  Scenario: A rerun keeps verdicts immutable and mints no duplicate accounts
    Given creditor accounts "A, B" already exist on file
    And an arrival with entries crediting accounts "A, B, X"
    And the PAI job has already run for the arrival
    When the PAI job reruns for the arrival
    Then the PAI job completes
    And the arrival has exactly 2 "EXISTS" and 1 "CREATED" verdicts
    And exactly one account row exists for each of "A, B, X"

  Scenario: An all-exist arrival is a valid no-op
    Given creditor accounts "A, B, C" already exist on file
    And an arrival with entries crediting accounts "A, B, C"
    When the PAI job runs for the arrival
    Then the PAI job completes
    And the arrival has exactly 3 "EXISTS" and 0 "CREATED" verdicts
    And no new accounts were minted

  Scenario: A minted account carries the synthetic ENDO defaults
    Given an arrival with entries crediting unknown accounts "X"
    When the PAI job runs for the arrival
    Then the PAI job completes
    And the arrival has exactly 0 "EXISTS" and 1 "CREATED" verdicts
    And the minted account "X" carries the synthetic ENDO defaults

  Scenario: A mixed arrival gets EXISTS and CREATED verdicts in one first run
    Given creditor accounts "A" already exist on file
    And an arrival with entries crediting accounts "A, X, Y"
    When the PAI job runs for the arrival
    Then the PAI job completes
    And the arrival has exactly 1 "EXISTS" and 2 "CREATED" verdicts
    And exactly one account row exists for each of "A, X, Y"
