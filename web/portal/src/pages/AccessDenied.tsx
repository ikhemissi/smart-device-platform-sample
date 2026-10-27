import { Body1, Title2, makeStyles, tokens } from '@fluentui/react-components'
import { LockClosedRegular } from '@fluentui/react-icons'

const useStyles = makeStyles({
  root: { maxWidth: '40rem', margin: '4rem auto', display: 'flex', flexDirection: 'column', gap: tokens.spacingVerticalM },
  icon: { fontSize: '48px', color: tokens.colorNeutralForeground3 },
})

/** Shown, without any data, to signed-in users holding no platform role. */
export function AccessDenied({ userPrincipalName }: { userPrincipalName: string }) {
  const styles = useStyles()
  return (
    <section className={styles.root} aria-labelledby="access-denied-title">
      <LockClosedRegular className={styles.icon} aria-hidden />
      <Title2 as="h1" id="access-denied-title">You do not have access to device data</Title2>
      <Body1>
        You are signed in as <strong>{userPrincipalName}</strong>, but this account has not been granted access to the
        Smart Device Platform.
      </Body1>
      <Body1>
        Ask an administrator to grant you the <strong>Query device data</strong> permission (or <strong>Edit device
        data</strong> to maintain device information), then sign in again.
      </Body1>
    </section>
  )
}
