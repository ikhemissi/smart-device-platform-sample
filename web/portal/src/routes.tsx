import { Navigate, type RouteObject } from 'react-router'
import { Shell } from './components/Shell'
import { DeviceListPage } from './pages/DeviceListPage'
import { DevicePage } from './pages/DevicePage'
import { NotFound } from './pages/NotFound'
import { SitePage } from './pages/sites/SitePage'
import { SitesPage } from './pages/sites/SitesPage'

export const routes: RouteObject[] = [
  {
    element: <Shell />,
    children: [
      { index: true, element: <Navigate to="/devices" replace /> },
      { path: 'devices', element: <DeviceListPage /> },
      { path: 'devices/:deviceId', element: <DevicePage /> },
      { path: 'sites', element: <SitesPage /> },
      { path: 'sites/:siteId', element: <SitePage /> },
      { path: '*', element: <NotFound /> },
    ],
  },
]
