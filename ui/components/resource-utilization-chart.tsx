"use client";

import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  ResponsiveContainer,
  Tooltip,
  Cell,
} from "recharts";
import type { Device } from "@/lib/api/devices";

interface ResourceUtilizationChartProps {
  devices: Device[];
}

export function ResourceUtilizationChart({ devices }: ResourceUtilizationChartProps) {
  const data = devices
    .filter((d) => d.status?.cpuUsagePercent != null)
    .sort((a, b) => (b.status?.cpuUsagePercent ?? 0) - (a.status?.cpuUsagePercent ?? 0))
    .slice(0, 5)
    .map((d) => ({
      name: d.hostname,
      cpu: Math.round(d.status?.cpuUsagePercent ?? 0),
    }));

  if (data.length === 0) {
    return (
      <div className="flex h-[250px] items-center justify-center text-sm text-muted-foreground">
        No resource data available
      </div>
    );
  }

  return (
    <ResponsiveContainer width="100%" height={250}>
      <BarChart data={data} layout="vertical" margin={{ left: 10, right: 20 }}>
        <XAxis type="number" domain={[0, 100]} unit="%" fontSize={12} />
        <YAxis
          type="category"
          dataKey="name"
          width={100}
          fontSize={12}
          tickLine={false}
        />
        <Tooltip formatter={(value: number) => [`${value}%`, "CPU"]} />
        <Bar dataKey="cpu" radius={[0, 4, 4, 0]} maxBarSize={24}>
          {data.map((entry) => (
            <Cell
              key={entry.name}
              fill={
                entry.cpu > 80
                  ? "#f87171"
                  : entry.cpu > 50
                    ? "#fbbf24"
                    : "#06b6d4"
              }
            />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}
