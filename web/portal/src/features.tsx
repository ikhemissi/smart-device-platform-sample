import { createContext, useContext } from 'react'

/** Optional parts of the portal, switched on per environment in config.json. */
export interface Features {
  assistant: boolean
  /** Anomaly detection (M7): the Anomalies page and the device page's card. */
  anomalies: boolean
  /** Sites analysed by the anomaly detector. */
  anomalySites: string[]
}

export const NO_FEATURES: Features = { assistant: false, anomalies: false, anomalySites: [] }

export const FeaturesContext = createContext<Features>(NO_FEATURES)

export function useFeatures(): Features {
  return useContext(FeaturesContext)
}
