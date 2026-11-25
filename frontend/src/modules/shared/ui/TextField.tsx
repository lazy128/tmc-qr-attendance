type TextFieldProps = {
  label: string;
  value: string;
  onChange: (value: string) => void;
};

export function TextField({ label, value, onChange }: TextFieldProps) {
  return (
    <label className="text-xs font-semibold text-slate-600">
      {label}
      <input
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="mt-2 block w-full rounded-lg border px-3 py-2.5 text-sm font-normal"
      />
    </label>
  );
}
