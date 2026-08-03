import { useCallback, useEffect, useState } from "react";

export function usePathname() {
  const [pathname, setPathname] = useState(() => window.location.pathname);

  useEffect(() => {
    const onPopState = () => setPathname(window.location.pathname);
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  const navigate = useCallback((to: string, replace = false) => {
    if (!to.startsWith("/") || to.startsWith("//")) {
      throw new Error("Only same-origin application paths can be navigated to.");
    }
    const target = new URL(to, window.location.origin);
    const safeTarget = `${target.pathname}${target.search}${target.hash}`;
    if (replace) window.history.replaceState({}, "", safeTarget);
    else window.history.pushState({}, "", safeTarget);
    window.dispatchEvent(new PopStateEvent("popstate"));
    window.scrollTo({ top: 0, behavior: "auto" });
  }, []);

  return { pathname, navigate };
}
