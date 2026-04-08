---
name: google-search
description: Search the web using Google Search, fetch full content from top pages, and answer based on actual page content.
metadata:
  homepage: https://developers.google.com/custom-search/v1/overview
---

# Google Search

## Description

Search the web and retrieve full content from the top result pages. Use the actual page content to answer the user's question with specific, accurate details.

## Examples

* "Search Google for the latest news about AI"
* "Google search: best Vietnamese restaurants in Hanoi"
* "Find information about climate change"
* "Search for: how to learn Kotlin"

## Instructions

Call the `run_js` tool with the following exact parameters:

- script name: `index.html`
- data: A JSON string with the following fields:
  - query: Required. The search query string extracted from the user's request.
  - num: Optional. Number of pages to fetch (1–5, default: 5).
  - lang: Optional. Language code (e.g., "vi", "en"). Default: "en".

**After receiving results:**
- The tool returns full text content from each web page under `[Source N]`.
- Read the content carefully and answer the user's question using **specific information from the page content**.
- Do NOT just summarize — extract exact facts, data, names, dates, numbers from the content.
- Cite which source (Source 1, 2, etc.) each piece of information comes from.
- Respond in the same language as the user's original message.
- If page content was not available (only snippet), note that and use the snippet.
