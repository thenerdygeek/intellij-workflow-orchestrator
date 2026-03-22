import { create } from 'zustand';

// Minimal stub store — real implementation in Task 4

interface SettingsStoreStub {
  visualizationsEnabled: boolean;
}

export const useSettingsStore = create<SettingsStoreStub>()(() => ({
  visualizationsEnabled: true,
}));
