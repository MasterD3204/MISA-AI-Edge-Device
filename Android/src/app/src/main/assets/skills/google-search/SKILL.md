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
* "Giá vàng hôm nay"
* "Tỷ giá USD hôm nay"
* "Thời tiết Hà Nội hôm nay"
* "Find information about climate change"

## Instructions

**CRITICAL: You MUST use the `run_js` tool. Do NOT use `run_intent`. Do NOT use any other tool.**

Step 1: Call the `run_js` tool with EXACTLY these parameters:
- skill name: `google-search`
- script name: `index.html`
- data: a JSON string with field `query` containing the search query

Example call:
- skill name: google-search
- script name: index.html
- data: {"query": "giá vàng hôm nay", "num": 5, "lang": "vi"}

Step 2: Wait for the tool to return. The result contains full web page content from top sources.

Step 3: Read the content from each `[Source N]` section carefully. Use the **actual text from the pages** to answer. Extract specific facts, numbers, dates directly from the content. Do NOT say you cannot access the internet — the tool already fetched the data for you.

Step 4: Cite sources. Respond in the same language as the user.
