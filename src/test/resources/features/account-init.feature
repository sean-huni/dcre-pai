@pai
Feature: PAI creditor account init for the ENDO flow

  Before the ENDO fork, PAI walks every spine transaction of an arrival and asks
  the account master one question per creditor: is this account known? A known
  account gets an EXISTS verdict. An absent one is RECORDED in PAI's own
  unknown_creditor relation and gets a CREATED verdict. Verdicts are immutable,
  so a rerun never flips CREATED to EXISTS (R-05), and the sighting is
  insert-once on the account number, so a rerun records no duplicate.

  SCRUM-107: the absent branch used to INSERT a hardcoded row into the account
  master, so PAI answered "this account does not exist" by making it exist, in a
  relation it does not own and PTV validates against. Every scenario below now
  asserts that the master is untouched, because that is the property that was
  wrong and the one a future change could quietly put back.

  Scenario: Entries referencing existing accounts receive EXISTS verdicts
    Given creditor accounts "A, B" already exist on file
    And an arrival with entries crediting accounts "A, B"
    When the PAI job runs for the arrival
    Then the PAI job completes
    And the arrival has exactly 2 "EXISTS" and 0 "CREATED" verdicts
    And the account master is unchanged
    And no creditor was recorded as unknown

  Scenario: Absent creditor accounts are recorded with CREATED verdicts
    Given an arrival with entries crediting unknown accounts "X, Y"
    When the PAI job runs for the arrival
    Then the PAI job completes
    And the arrival has exactly 0 "EXISTS" and 2 "CREATED" verdicts
    And the account master is unchanged
    And exactly one unknown-creditor record exists for each of "X, Y"

  Scenario: A rerun keeps verdicts immutable and records no duplicate sighting
    Given creditor accounts "A, B" already exist on file
    And an arrival with entries crediting accounts "A, B, X"
    And the PAI job has already run for the arrival
    When the PAI job reruns for the arrival
    Then the PAI job completes
    And the arrival has exactly 2 "EXISTS" and 1 "CREATED" verdicts
    And the account master is unchanged
    And exactly one unknown-creditor record exists for each of "X"

  Scenario: An all-exist arrival is a valid no-op
    Given creditor accounts "A, B, C" already exist on file
    And an arrival with entries crediting accounts "A, B, C"
    When the PAI job runs for the arrival
    Then the PAI job completes
    And the arrival has exactly 3 "EXISTS" and 0 "CREATED" verdicts
    And the account master is unchanged
    And no creditor was recorded as unknown

  Scenario: A recorded sighting names the arrival that observed it and nothing else
    Given an arrival with entries crediting unknown accounts "X"
    When the PAI job runs for the arrival
    Then the PAI job completes
    And the arrival has exactly 0 "EXISTS" and 1 "CREATED" verdicts
    And the unknown-creditor record for "X" names this arrival
    And the account master is unchanged

  Scenario: A mixed arrival gets EXISTS and CREATED verdicts in one first run
    Given creditor accounts "A" already exist on file
    And an arrival with entries crediting accounts "A, X, Y"
    When the PAI job runs for the arrival
    Then the PAI job completes
    And the arrival has exactly 1 "EXISTS" and 2 "CREATED" verdicts
    And the account master is unchanged
    And exactly one unknown-creditor record exists for each of "X, Y"
