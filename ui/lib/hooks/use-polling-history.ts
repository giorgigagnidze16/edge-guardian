"use client";

import { useRef, useCallback } from "react";

const MAX_POINTS = 30;

export function usePollingHistory(maxPoints = MAX_POINTS) {
  const historyRef = useRef<Map<string, number[]>>(new Map());

  const push = useCallback(
    (key: string, value: number | undefined | null) => {
      if (value == null || isNaN(value)) return;
      const history = historyRef.current;
      const arr = history.get(key) ?? [];
      arr.push(value);
      if (arr.length > maxPoints) {
        arr.splice(0, arr.length - maxPoints);
      }
      history.set(key, arr);
    },
    [maxPoints],
  );

  const get = useCallback((key: string): number[] => {
    return historyRef.current.get(key) ?? [];
  }, []);

  return { push, get };
}
