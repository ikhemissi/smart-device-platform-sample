import { createContext, useContext } from 'react'

/** Optional parts of the portal, switched on per environment in config.json. */
export interface Features {
  assistant: boolean
}

export const FeaturesContext = createContext<Features>({ assistant: false })

export function useFeatures(): Features {
  return useContext(FeaturesContext)
}
