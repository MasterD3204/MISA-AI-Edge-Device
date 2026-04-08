---
name: google-search
description: Search the web using Google Search and return top results.
metadata:
  homepage: https://developers.google.com/custom-search/v1/overview
---

# Google Search

## Description

Search the web using Google Custom Search API and return the top results including titles, URLs, and snippets.

## Examples

* "Search Google for the latest news about AI"
* "Google search: best Vietnamese restaurants in Hanoi"
* "Find information about climate change on Google"
* "Search for: how to learn Kotlin"

## Instructions

Call the `run_js` tool with the following exact parameters:

- script name: `index.html`
- data: A JSON string with the following fields:
  - query: Required. The search query string extracted from the user's request.
  - num: Optional. Number of results to return (1–10, default: 5).
  - lang: Optional. Language code for results (e.g., "vi" for Vietnamese, "en" for English). Default: "en".

**Constraints:**
- Always extract a clean, concise search query from the user's message.
- Present results as a numbered list with title, URL, and a brief snippet.
- Summarize the most relevant findings in 2–3 sentences after listing results.
- Respond in the same language as the user's original message.
- If no results are found, inform the user and suggest refining the query.
