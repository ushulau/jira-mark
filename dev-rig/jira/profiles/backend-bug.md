---
schemaVersion: 1
profile: backend-bug
project: APP
issueType: Bug
fields:
  summary:
    type: string
    required: true
  description:
    type: string
    required: false
  labels:
    type: string-list
    default: [backend]
  priority:
    type: object
    default: {name: Medium}
  acceptanceCriteria:
    id: customfield_10042
    type: string
    required: false
---

# Backend bug

Use for reproducible defects in backend services. Use a short, specific summary.

Describe reproduction steps, expected behavior, actual behavior, and affected
versions. Link to the relevant source file or commit when helpful. Remove private
customer data from logs before including them.

The APP project and custom field ID above are examples. Replace them with your
instance's configuration before using a Jira creation command.
