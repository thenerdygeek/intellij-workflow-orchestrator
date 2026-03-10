This consolidated requirement document outlines the vision for an **IntelliJ IDEA Workflow Orchestrator Plugin**. The goal is to eliminate context-switching between Jira, Bamboo, SonarQube, and Bitbucket by consolidating the entire development lifecycle into a single, user-centric IDE interface.

---

## I. Core Objectives

* **Reduce Context Switching:** Centralize all external tool interactions within IntelliJ.
* **Enforce Quality Gates:** Automate the verification of SonarQube and Build requirements.
* **Orchestrate Automation:** Manage the "single-user" bottleneck of the automation suite via a smart queue and configuration staging area.
* **User Personalization:** Filter all data (tickets, builds, runs) to the specific logged-in developer.

---

## II. Authentication & User Context

* **Unified Login:** Support for Personal Access Tokens (PAT) or OAuth for Jira, Bamboo, and Bitbucket.
* **User Filtering:** * View only Jira tickets assigned to the authenticated user.
* Show only "My Builds" in the Bamboo CI monitor.
* Persistence of "Last Run" configurations (the most recent `dockerTagsAsJson` used by the user).



---

## III. Module 1: Sprint & Branch Management

* **Active Sprint View:** A tool window listing assigned tickets.
* **Smart Branching:** * One-click branch creation from a selected ticket.
* Automatic transition of Jira ticket to "In Progress."


* **Commit Message Template:** Automatic prefixing of the current Jira Ticket ID to all commit messages within the IDE.
* **Local Health Check:** Integrated button to run `mvn clean install` and verify application startup before allowing a "Push to Origin."

---

## IV. Module 2: CI & Sonar Monitoring

* **Parallel Build Dashboard:** A real-time monitor for the three Bamboo actions:
1. **Build Artifact:** Success/Failure of the Maven build.
2. **OSS Analysis:** Security vulnerability report.
3. **SonarQube:** Visual pass/fail status based on the specific thresholds (100% new code coverage, 95% branch coverage).


* **In-Editor Coverage Highlighting:** If Sonar fails, the plugin should fetch coverage data and highlight the specific lines/branches in the code editor that are missing tests.
* **Remediation Integration:** Right-click context menu to send failing Sonar issues or uncovered lines to an AI assistant (like Cody) for fix generation.

---

## V. Module 3: Automation Suite Orchestrator

This is the central "Staging Area" for triggering regression tests.

### **1. Configuration Staging Area (UI)**

* **Service Table:** A list of all microservices involved in the automation suite.
* **Tag Intelligence:** * Automatically detect and populate the "Feature Branch Docker Tag" for the service currently being developed.
* Default other services to their latest "Release Tags" from the most recent successful system-wide run.


* **JSON Generator:** A live-updating UI that constructs the `dockerTagsAsJson` payload based on user selections in the table.

### **2. Smart Queuing System**

* **Concurrency Monitor:** Real-time visibility into whether the Bamboo automation branch is currently occupied.
* **Queue Enrollment:** If the suite is busy, the user can click "Queue Run."
* **Auto-Trigger:** The plugin polls the Bamboo API and automatically triggers the user's "Staged Configuration" as soon as the current run finishes and the suite becomes "Idle."

---

## VI. Module 4: Handover & Closure

* **Automated Jira Commenting:** Upon completion of a successful automation run, a "Complete Task" button will:
1. Fetch the successful Docker Tags.
2. Generate a summary of the test results and Bamboo links.
3. Post this as a comment on the linked Jira ticket.


* **Status Transition:** Automatically move the Jira ticket from "In Progress" to "In Review."

---

## VII. Non-Functional Requirements

* **Performance:** API polling for build status should happen on a background thread to prevent IDE lag.
* **Persistent State:** Store the last 5 `dockerTagsAsJson` configurations locally so users can quickly revert or compare versions.
* **Notifications:** System-level toast notifications for "Build Finished," "Queue Turn Started," or "Sonar Failure."

---

**Would you like me to create a technical architecture diagram or a prioritized "Phase 1" feature list to begin development?**