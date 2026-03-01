"use client";

import { PieChart, Pie, Cell, ResponsiveContainer, Legend, Tooltip } from "recharts";

interface FleetHealthChartProps {
  online: number;
  degraded: number;
  offline: number;
}

const COLORS: Record<string, string> = {
  Online: "#34d399",
  Degraded: "#fbbf24",
  Offline: "#71717a",
};

export function FleetHealthChart({ online, degraded, offline }: FleetHealthChartProps) {
  const data = [
    { name: "Online", value: online },
    { name: "Degraded", value: degraded },
    { name: "Offline", value: offline },
  ].filter((d) => d.value > 0);

  if (data.length === 0) {
    return (
      <div className="flex h-[300px] items-center justify-center text-sm text-muted-foreground">
        No devices registered yet
      </div>
    );
  }

  return (
    <ResponsiveContainer width="100%" height={300}>
      <PieChart>
        <Pie
          data={data}
          cx="50%"
          cy="50%"
          innerRadius={60}
          outerRadius={100}
          paddingAngle={2}
          dataKey="value"
          label={({ name, percent }) =>
            `${name} ${(percent * 100).toFixed(0)}%`
          }
        >
          {data.map((entry) => (
            <Cell
              key={entry.name}
              fill={COLORS[entry.name]}
              strokeWidth={0}
            />
          ))}
        </Pie>
        <Tooltip />
        <Legend />
      </PieChart>
    </ResponsiveContainer>
  );
}
