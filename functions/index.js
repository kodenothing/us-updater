const admin = require('firebase-admin');
const { onDocumentCreated, onDocumentWritten } = require('firebase-functions/v2/firestore');

admin.initializeApp();
const db = admin.firestore();

function categoryFor(type) {
  if (['todo_created', 'bucket_created', 'todo_completed'].includes(type)) return 'lists';
  if (['event_created', 'event_updated'].includes(type)) return 'events';
  if (type === 'note_created') return 'notes';
  if (type === 'memory_created') return 'memories';
  if (['daily_answered', 'daily_checkin'].includes(type)) return 'daily';
  return 'other';
}

exports.notifyActivityCreated = onDocumentCreated(
  'couples/{coupleId}/activity/{activityId}',
  async (event) => {
    const activity = event.data && event.data.data();
    if (!activity || !activity.actorUid) return;

    const coupleId = event.params.coupleId;
    const activityId = event.params.activityId;
    const coupleSnap = await db.doc(`couples/${coupleId}`).get();
    if (!coupleSnap.exists) return;

    const members = coupleSnap.get('members') || [];
    const partnerUid = members.find((uid) => uid !== activity.actorUid);
    if (!partnerUid) return;

    const memberNames = coupleSnap.get('memberNames') || {};
    const actorName = String(memberNames[activity.actorUid] || 'Your person').trim().slice(0, 24);
    const action = String(activity.title || 'shared something').trim().replace(/^./, (letter) => letter.toLowerCase());
    const title = `${actorName} ${action}`;
    const body = String(activity.summary || 'Open Us to see what is new.').slice(0, 180);

    const deviceSnap = await db.doc(`couples/${coupleId}/devices/${partnerUid}`).get();
    const token = deviceSnap.exists ? deviceSnap.get('token') : null;
    if (!token || deviceSnap.get('notificationsEnabled') === false) return;

    try {
      await admin.messaging().send({
        token,
        data: {
          title: title.slice(0, 80),
          body,
          coupleId,
          category: categoryFor(activity.type),
          notificationId: `${coupleId}:activity:${activityId}`.slice(0, 180)
        },
        android: { priority: 'high' }
      });
    } catch (error) {
      if (error.code === 'messaging/registration-token-not-registered' ||
          error.code === 'messaging/invalid-registration-token') {
        await deviceSnap.ref.delete().catch(() => {});
        return;
      }
      console.error('Partner activity notification failed', error);
    }
  }
);


// Refresh every registered home-screen Live Photo Widget after its shared
// Firestore pointer changes. This is a data-only message, not a user alert.
exports.syncLivePhotoWidget = onDocumentWritten(
  'couples/{coupleId}/livePhotoWidget/current',
  async (event) => {
    const coupleId = event.params.coupleId;
    const coupleSnap = await db.doc(`couples/${coupleId}`).get();
    if (!coupleSnap.exists) return;

    const members = coupleSnap.get('members') || [];
    const sends = await Promise.all(members.map(async (uid) => {
      const deviceRef = db.doc(`couples/${coupleId}/devices/${uid}`);
      const deviceSnap = await deviceRef.get();
      const token = deviceSnap.exists ? deviceSnap.get('token') : null;
      if (!token) return;

      try {
        await admin.messaging().send({
          token,
          data: {
            type: 'live_photo_widget',
            coupleId,
            widgetUpdatedAt: String(Date.now())
          },
          android: { priority: 'high' }
        });
      } catch (error) {
        if (error.code === 'messaging/registration-token-not-registered' ||
            error.code === 'messaging/invalid-registration-token') {
          await deviceRef.delete().catch(() => {});
          return;
        }
        console.error('Live Photo Widget refresh failed', error);
      }
    }));
    return sends;
  }
);
