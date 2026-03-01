"use client";

import {
  Area,
  AreaChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

interface DeviceResourceChartProps {
  data: Array<{ index: number; cpu: number; memory: number }>;
}

export function DeviceResourceChart({ data }: DeviceResourceChartProps) {
  return (
    <ResponsiveContainer width="100%" height={250}>
      <AreaChart data={data}>
        <XAxis dataKey="index" hide />
        <YAxis domain={[0, 100]} tickFormatter={(v) => `${v}%`} width={40} />
        <Tooltip formatter={(v: number) => `${v.toFixed(1)}%`} />
        <Area
          type="monotone"
          dataKey="cpu"
          stroke="var(--color-chart-1)"
          fill="var(--color-chart-1)"
          fillOpacity={0.15}
          name="CPU"
        />
        <Area
          type="monotone"
          dataKey="memory"
          stroke="var(--color-chart-4)"
          fill="var(--color-chart-4)"
          fillOpacity={0.15}
          name="Memory"
        />
      </AreaChart>
    </ResponsiveContainer>
  );
}