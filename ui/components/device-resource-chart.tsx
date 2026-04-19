"use client";

import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { format } from "date-fns";

export interface DeviceResourcePoint {
  time: string;
  cpu: number;
  memory: number;
  temperature?: number;
}

interface DeviceResourceChartProps {
  data: DeviceResourcePoint[];
  showTemperature?: boolean;
  axisFormat?: "hour" | "day";
}

export function DeviceResourceChart({
  data,
  showTemperature = false,
  axisFormat = "hour",
}: DeviceResourceChartProps) {
  const formatAxisTime = (iso: string) =>
    format(new Date(iso), axisFormat === "day" ? "MMM d HH:mm" : "HH:mm");

  const formatTooltipTime = (iso: string) =>
    format(new Date(iso), "MMM d, yyyy HH:mm:ss");

  return (
    <ResponsiveContainer width="100%" height={260}>
      <AreaChart data={data} margin={{ top: 5, right: 10, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" opacity={0.2} />
        <XAxis
          dataKey="time"
          tickFormatter={formatAxisTime}
          tick={{ fontSize: 11 }}
          minTickGap={40}
        />
        <YAxis
          yAxisId="pct"
          domain={[0, 100]}
          tickFormatter={(v) => `${v}%`}
          tick={{ fontSize: 11 }}
          width={42}
        />
        {showTemperature && (
          <YAxis
            yAxisId="temp"
            orientation="right"
            tickFormatter={(v) => `${v}°C`}
            tick={{ fontSize: 11 }}
            width={42}
          />
        )}
        <Tooltip
          labelFormatter={(label) => formatTooltipTime(label as string)}
          formatter={(v: number, name) => {
            if (name === "Temperature") return [`${v.toFixed(1)}°C`, name];
            return [`${v.toFixed(1)}%`, name];
          }}
        />
        <Legend iconType="line" />
        <Area
          yAxisId="pct"
          type="monotone"
          dataKey="cpu"
          stroke="var(--color-chart-1)"
          fill="var(--color-chart-1)"
          fillOpacity={0.15}
          name="CPU"
          isAnimationActive={false}
        />
        <Area
          yAxisId="pct"
          type="monotone"
          dataKey="memory"
          stroke="var(--color-chart-4)"
          fill="var(--color-chart-4)"
          fillOpacity={0.15}
          name="Memory"
          isAnimationActive={false}
        />
        {showTemperature && (
          <Area
            yAxisId="temp"
            type="monotone"
            dataKey="temperature"
            stroke="var(--color-chart-5, #ef4444)"
            fill="var(--color-chart-5, #ef4444)"
            fillOpacity={0.1}
            name="Temperature"
            isAnimationActive={false}
          />
        )}
      </AreaChart>
    </ResponsiveContainer>
  );
}
