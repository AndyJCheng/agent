import asyncio
try:
    import aiohttp
except ModuleNotFoundError as exc:
    if exc.name != "aiohttp":
        raise
    raise SystemExit("Missing dependency: install with pip install -r tool/requirements.txt") from exc
from typing import List, Dict

async def check_url_status(session: aiohttp.ClientSession, url: str) -> Dict:
    """Asynchronously checks the HTTP status code of a given URL."""
    try:
        # Set a reasonable timeout for the request
        async with session.get(url, timeout=aiohttp.ClientTimeout(total=10)) as response:
            # We only care about the status code here
            return {"url": url, "status": response.status, "success": 200 <= response.status < 300}
    except aiohttp.ClientConnectorError:
        return {"url": url, "status": "Connection Error", "success": False}
    except asyncio.TimeoutError:
        return {"url": url, "status": "Timeout Error", "success": False}
    except Exception as e:
        return {"url": url, "status": f"Error: {type(e).__name__}", "success": False}

async def check_multiple_urls(urls: List[str]) -> List[Dict]:
    """
    Asynchronously checks the status of a list of URLs using a shared ClientSession.
    """
    print(f"Starting status check for {len(urls)} URLs...")
    # Use a single ClientSession for efficiency (connection pooling)
    async with aiohttp.ClientSession() as session:
        tasks = [check_url_status(session, url) for url in urls]
        # Gather results concurrently
        results = await asyncio.gather(*tasks)
        return results

async def main():
    # --- Example Usage ---
    urls_to_check = [
        "https://www.google.com",         # Should succeed (2xx)
        "https://httpbin.org/status/404", # Should return 404
        "http://thisshouldnotexist12345.com", # Should connection error
        "https://www.python.org",         # Should succeed (2xx)
        "https://api.github.com/nonexistent/endpoint" # Might give 404 or timeout depending on rate limits/implementation
    ]
    
    # Note: For the timeout test, you might need a known slow endpoint, 
    # but we'll rely on the other examples for demonstration.

    results = await check_multiple_urls(urls_to_check)
    
    print("\n--- Summary ---")
    for result in results:
        status_display = f"Status: {result['status']}"
        if result['success']:
            status_display += " (OK)"
        else:
            status_display += " (FAILED)"
        print(f"[{result['success']}] {result['url']}\n\t{status_display}")

if __name__ == "__main__":
    asyncio.run(main())
