const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds
} = require('@firebase/rules-unit-testing');
const {
  Bytes,
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  orderBy,
  query,
  setDoc,
  updateDoc,
  writeBatch,
  serverTimestamp
} = require('firebase/firestore');

const PROJECT_ID = 'us-firestore-rules-tests';
const COUPLE_ID = 'USCOUPLE12AB';
const MEMBER_A = 'member-james';
const MEMBER_B = 'member-mae';
const OUTSIDER = 'not-a-member';
let environment;

const ref = (db, ...segments) => doc(db, ...segments);
const couplePath = `couples/${COUPLE_ID}`;

test.before(async () => {
  const rules = fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8');
  environment = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules }
  });
});

test.beforeEach(async () => {
  await environment.clearFirestore();
  await environment.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(ref(db, couplePath), {
      members: [MEMBER_A, MEMBER_B],
      createdAt: 1,
      anniversary: 1,
      coupleName: 'Us',
      memberNames: { [MEMBER_A]: 'James', [MEMBER_B]: 'Mae' },
      deleteApprovals: {}
    });
    await setDoc(ref(db, couplePath, 'daily', '2026-11-02'), {
      dateKey: '2026-11-02',
      question: 'What made you feel loved today?',
      questionIndex: 0,
      createdAt: 1
    });
  });
});

test.after(async () => {
  if (environment) await environment.cleanup();
});

test('only the two paired members can read the couple document', async () => {
  const memberDb = environment.authenticatedContext(MEMBER_A).firestore();
  const outsiderDb = environment.authenticatedContext(OUTSIDER).firestore();
  await assertSucceeds(getDoc(ref(memberDb, couplePath)));
  await assertFails(getDoc(ref(outsiderDb, couplePath)));
});

test('new shared spaces must begin as one-person pending invitations', async () => {
  const db = environment.authenticatedContext(MEMBER_A).firestore();
  const valid = ref(db, 'couples/NEWPENDINGINVITE');
  const base = {
    members: [MEMBER_A], createdAt: 1, anniversary: 1, coupleName: 'Us',
    memberNames: { [MEMBER_A]: 'James' }, deleteApprovals: {}
  };
  await assertSucceeds(setDoc(valid, { ...base, inviteOnly: true }));
  await assertFails(setDoc(ref(db, 'couples/MISSINGINVITEMARKER'), base));
});

test('a member can edit only their own nickname', async () => {
  const db = environment.authenticatedContext(MEMBER_A).firestore();
  const couple = ref(db, couplePath);
  await assertSucceeds(updateDoc(couple, { [`memberNames.${MEMBER_A}`]: 'James J.' }));
  await assertFails(updateDoc(couple, { [`memberNames.${MEMBER_B}`]: 'Impersonated' }));
});

test('each paired member can set only their own gender value', async () => {
  const aDb = environment.authenticatedContext(MEMBER_A).firestore();
  const bDb = environment.authenticatedContext(MEMBER_B).firestore();
  const outsiderDb = environment.authenticatedContext(OUTSIDER).firestore();
  const aCouple = ref(aDb, couplePath);
  const bCouple = ref(bDb, couplePath);
  const outsiderCouple = ref(outsiderDb, couplePath);

  // Legacy couple documents without memberGenders accept the first own-field update.
  await assertSucceeds(updateDoc(aCouple, { [`memberGenders.${MEMBER_A}`]: 'male' }));
  await assertSucceeds(updateDoc(bCouple, { [`memberGenders.${MEMBER_B}`]: 'female' }));

  // Both paired members can read the profile map for perspective-aware UI labels.
  const aView = await getDoc(aCouple);
  const bView = await getDoc(bCouple);
  assert.equal(aView.data().memberGenders[MEMBER_A], 'male');
  assert.equal(aView.data().memberGenders[MEMBER_B], 'female');
  assert.equal(bView.data().memberGenders[MEMBER_A], 'male');
  assert.equal(bView.data().memberGenders[MEMBER_B], 'female');

  await assertSucceeds(updateDoc(aCouple, { [`memberGenders.${MEMBER_A}`]: 'unspecified' }));
  await assertFails(updateDoc(aCouple, { [`memberGenders.${MEMBER_B}`]: 'unspecified' }));
  await assertFails(updateDoc(bCouple, { [`memberGenders.${MEMBER_A}`]: 'female' }));
  await assertFails(updateDoc(aCouple, { [`memberGenders.${MEMBER_A}`]: 'other' }));
  await assertFails(updateDoc(aCouple, {
    [`memberGenders.${MEMBER_A}`]: 'male',
    coupleName: 'Impersonated update'
  }));
  await assertFails(updateDoc(outsiderCouple, { [`memberGenders.${OUTSIDER}`]: 'male' }));
});

test('a third account cannot join an already paired couple', async () => {
  const db = environment.authenticatedContext(OUTSIDER).firestore();
  await assertFails(updateDoc(ref(db, couplePath), {
    members: [MEMBER_A, MEMBER_B, OUTSIDER]
  }));
});

test('only the creator can cancel a pending invite, and a joined invite cannot be cancelled', async () => {
  const aDb = environment.authenticatedContext(MEMBER_A).firestore();
  const bDb = environment.authenticatedContext(MEMBER_B).firestore();
  const pendingPath = 'couples/PENDINGINVITE01';
  const joinedPath = 'couples/JOINEDINVITE001';

  await environment.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(ref(db, pendingPath), {
      members: [MEMBER_A], createdAt: 1, anniversary: 1, coupleName: 'Us',
      memberNames: { [MEMBER_A]: 'James' }, deleteApprovals: {}, inviteOnly: true
    });
    await setDoc(ref(db, joinedPath), {
      members: [MEMBER_A], createdAt: 2, anniversary: 1, coupleName: 'Us',
      memberNames: { [MEMBER_A]: 'James' }, deleteApprovals: {}, inviteOnly: true
    });
  });

  await assertFails(deleteDoc(ref(bDb, pendingPath)));
  await assertSucceeds(deleteDoc(ref(aDb, pendingPath)));

  await assertSucceeds(updateDoc(ref(bDb, joinedPath), {
    members: [MEMBER_A, MEMBER_B],
    [`memberNames.${MEMBER_B}`]: 'Mae',
    inviteOnly: false,
    deleteApprovals: {}
  }));
  await assertFails(deleteDoc(ref(aDb, joinedPath)));
});

test('daily answers stay private until the reader has submitted theirs', async () => {
  const memberDb = environment.authenticatedContext(MEMBER_A).firestore();
  const partnerDb = environment.authenticatedContext(MEMBER_B).firestore();
  const memberAAnswer = ref(memberDb, couplePath, 'daily', '2026-11-02', 'answers', MEMBER_A);
  const memberBAnswerFromA = ref(memberDb, couplePath, 'daily', '2026-11-02', 'answers', MEMBER_B);
  await assertFails(getDoc(memberBAnswerFromA));
  await assertSucceeds(setDoc(memberAAnswer, {
    uid: MEMBER_A, answer: 'You made me laugh.', createdAt: 1, updatedAt: 1
  }));
  await assertSucceeds(setDoc(ref(partnerDb, couplePath, 'daily', '2026-11-02', 'answers', MEMBER_B), {
    uid: MEMBER_B, answer: 'You remembered my coffee.', createdAt: 2, updatedAt: 2
  }));
  await assertSucceeds(getDoc(memberBAnswerFromA));
  await assertSucceeds(getDoc(ref(partnerDb, couplePath, 'daily', '2026-11-02', 'answers', MEMBER_A)));
});

test('daily answers can be edited only by their owner without changing the original timestamp', async () => {
  const aDb = environment.authenticatedContext(MEMBER_A).firestore();
  const bDb = environment.authenticatedContext(MEMBER_B).firestore();
  const ownAnswer = ref(aDb, couplePath, 'daily', '2026-11-02', 'answers', MEMBER_A);
  const partnerView = ref(bDb, couplePath, 'daily', '2026-11-02', 'answers', MEMBER_A);
  await assertSucceeds(setDoc(ownAnswer, {
    uid: MEMBER_A, answer: 'A first answer.', createdAt: 100, updatedAt: 100
  }));
  await assertSucceeds(updateDoc(ownAnswer, { answer: 'An updated answer.', updatedAt: 200 }));
  await assertFails(updateDoc(ownAnswer, { createdAt: 300, updatedAt: 300 }));
  await assertFails(updateDoc(partnerView, { answer: 'Changed by the other person.', updatedAt: 400 }));
});

test('memory originals and thumbnails enforce separate byte limits', async () => {
  const db = environment.authenticatedContext(MEMBER_A).firestore();
  const memory = ref(db, couplePath, 'memories', 'memory-one');
  await assertSucceeds(setDoc(memory, {
    caption: 'A day together',
    fileName: 'memory-one.jpg',
    photoUrl: '',
    storagePath: '',
    storageMode: 'firestore_blob',
    status: 'pending',
    createdBy: MEMBER_A,
    createdAt: 1,
    favorite: false,
    sizeBytes: 0,
    thumbnailBytes: 0
  }));
  await assertSucceeds(setDoc(ref(db, couplePath, 'memories', 'memory-one', 'content', 'image'), {
    image: Bytes.fromUint8Array(new Uint8Array(700 * 1024)),
    contentType: 'image/jpeg', createdBy: MEMBER_A, createdAt: 1
  }));
  await assertSucceeds(setDoc(ref(db, couplePath, 'memories', 'memory-one', 'content', 'thumbnail'), {
    image: Bytes.fromUint8Array(new Uint8Array(80 * 1024)),
    contentType: 'image/jpeg', createdBy: MEMBER_A, createdAt: 1
  }));

  const secondMemory = ref(db, couplePath, 'memories', 'memory-two');
  await assertSucceeds(setDoc(secondMemory, {
    caption: 'Another day', fileName: 'memory-two.jpg', photoUrl: '', storagePath: '',
    storageMode: 'firestore_blob', status: 'pending', createdBy: MEMBER_A,
    createdAt: 2, favorite: false, sizeBytes: 0, thumbnailBytes: 0
  }));
  await assertFails(setDoc(ref(db, couplePath, 'memories', 'memory-two', 'content', 'thumbnail'), {
    image: Bytes.fromUint8Array(new Uint8Array(81 * 1024)),
    contentType: 'image/jpeg', createdBy: MEMBER_A, createdAt: 2
  }));
});

test('Our Instant history preserves previous captures and allows only couple members to browse them', async () => {
  const aDb = environment.authenticatedContext(MEMBER_A).firestore();
  const bDb = environment.authenticatedContext(MEMBER_B).firestore();
  const outsiderDb = environment.authenticatedContext(OUTSIDER).firestore();

  const createReadyPhoto = async (db, id, ownerUid, createdAt) => {
    const photo = ref(db, couplePath, 'livePhotoWidgetPhotos', id);
    await assertSucceeds(setDoc(photo, {
      createdBy: ownerUid,
      createdAt,
      status: 'pending',
      sizeBytes: 0,
      thumbnailBytes: 0
    }));
    const bytes = Bytes.fromUint8Array(new Uint8Array([255, 216, 255]));
    await assertSucceeds(setDoc(ref(db, couplePath, 'livePhotoWidgetPhotos', id, 'content', 'image'), {
      image: bytes, contentType: 'image/jpeg', createdBy: ownerUid, createdAt
    }));
    await assertSucceeds(setDoc(ref(db, couplePath, 'livePhotoWidgetPhotos', id, 'content', 'thumbnail'), {
      image: bytes, contentType: 'image/jpeg', createdBy: ownerUid, createdAt
    }));
    await assertSucceeds(updateDoc(photo, {
      status: 'ready',
      sizeBytes: 3,
      thumbnailBytes: 3,
      previousPhotoId: ''
    }));
  };

  // James captures first; Mae's later capture becomes current without deleting his history.
  await createReadyPhoto(aDb, 'history-james-old', MEMBER_A, 100);
  await createReadyPhoto(bDb, 'history-mae-new', MEMBER_B, 200);
  await assertSucceeds(setDoc(ref(bDb, couplePath, 'livePhotoWidget', 'current'), {
    activePhotoId: 'history-mae-new',
    updatedBy: MEMBER_B,
    updatedAt: 200
  }));

  const jamesHistory = await getDocs(query(
    collection(aDb, couplePath, 'livePhotoWidgetPhotos'),
    orderBy('createdAt', 'desc')
  ));
  const jamesViewFromMae = await getDocs(query(
    collection(bDb, couplePath, 'livePhotoWidgetPhotos'),
    orderBy('createdAt', 'desc')
  ));

  assert.deepEqual(jamesHistory.docs.map((entry) => entry.id), [
    'history-mae-new',
    'history-james-old'
  ]);
  assert.deepEqual(
    jamesViewFromMae.docs.map((entry) => entry.id),
    ['history-mae-new', 'history-james-old']
  );

  await assertSucceeds(getDoc(ref(bDb, couplePath, 'livePhotoWidgetPhotos', 'history-james-old')));
  await assertSucceeds(getDoc(ref(bDb, couplePath, 'livePhotoWidgetPhotos', 'history-james-old', 'content', 'image')));
  await assertFails(getDocs(query(
    collection(outsiderDb, couplePath, 'livePhotoWidgetPhotos'),
    orderBy('createdAt', 'desc')
  )));
});

test('Our Instant gallery history deletion is owner-only and active-photo removal remains available to either partner', async () => {
  const aDb = environment.authenticatedContext(MEMBER_A).firestore();
  const bDb = environment.authenticatedContext(MEMBER_B).firestore();
  const bytes = Bytes.fromUint8Array(new Uint8Array([1, 2, 3]));

  const oldPhoto = ref(aDb, couplePath, 'livePhotoWidgetPhotos', 'old-photo-a');
  const oldImage = ref(aDb, couplePath, 'livePhotoWidgetPhotos', 'old-photo-a', 'content', 'image');
  const oldThumb = ref(aDb, couplePath, 'livePhotoWidgetPhotos', 'old-photo-a', 'content', 'thumbnail');
  await assertSucceeds(setDoc(oldPhoto, {
    createdBy: MEMBER_A, createdAt: 100, status: 'pending', sizeBytes: 0, thumbnailBytes: 0
  }));
  await assertSucceeds(setDoc(oldImage, {
    image: bytes, contentType: 'image/jpeg', createdBy: MEMBER_A, createdAt: 100
  }));
  await assertSucceeds(setDoc(oldThumb, {
    image: bytes, contentType: 'image/jpeg', createdBy: MEMBER_A, createdAt: 100
  }));
  await assertSucceeds(updateDoc(oldPhoto, {
    status: 'ready', sizeBytes: 3, thumbnailBytes: 3, previousPhotoId: ''
  }));

  // The other member can view history, but cannot delete a photo they do not own.
  await assertSucceeds(getDoc(ref(bDb, couplePath, 'livePhotoWidgetPhotos', 'old-photo-a')));
  await assertFails(deleteDoc(ref(bDb, couplePath, 'livePhotoWidgetPhotos', 'old-photo-a', 'content', 'image')));
  await assertFails(deleteDoc(ref(bDb, couplePath, 'livePhotoWidgetPhotos', 'old-photo-a')));
  // The owner can delete their own non-current history entry.
  await assertSucceeds(deleteDoc(oldImage));
  await assertSucceeds(deleteDoc(oldThumb));
  await assertSucceeds(deleteDoc(oldPhoto));

  // Removing the active shared photo remains a two-person shared action.
  const active = ref(aDb, couplePath, 'livePhotoWidgetPhotos', 'active-photo');
  await assertSucceeds(setDoc(active, {
    createdBy: MEMBER_A, createdAt: 200, status: 'pending', sizeBytes: 0, thumbnailBytes: 0
  }));
  await assertSucceeds(setDoc(ref(aDb, couplePath, 'livePhotoWidgetPhotos', 'active-photo', 'content', 'image'), {
    image: bytes, contentType: 'image/jpeg', createdBy: MEMBER_A, createdAt: 200
  }));
  await assertSucceeds(setDoc(ref(aDb, couplePath, 'livePhotoWidgetPhotos', 'active-photo', 'content', 'thumbnail'), {
    image: bytes, contentType: 'image/jpeg', createdBy: MEMBER_A, createdAt: 200
  }));
  await assertSucceeds(updateDoc(active, {
    status: 'ready', sizeBytes: 3, thumbnailBytes: 3, previousPhotoId: ''
  }));
  const current = ref(aDb, couplePath, 'livePhotoWidget', 'current');
  await assertSucceeds(setDoc(current, {
    activePhotoId: 'active-photo', updatedBy: MEMBER_A, updatedAt: 201
  }));
  await assertSucceeds(updateDoc(ref(bDb, couplePath, 'livePhotoWidget', 'current'), {
    activePhotoId: '', updatedBy: MEMBER_B, updatedAt: 202
  }));
  await assertSucceeds(deleteDoc(ref(bDb, couplePath, 'livePhotoWidgetPhotos', 'active-photo', 'content', 'image')));
  await assertSucceeds(deleteDoc(ref(bDb, couplePath, 'livePhotoWidgetPhotos', 'active-photo', 'content', 'thumbnail')));
  await assertSucceeds(deleteDoc(ref(bDb, couplePath, 'livePhotoWidgetPhotos', 'active-photo')));
});

test('permanent deletion requires both partner approvals', async () => {
  const aDb = environment.authenticatedContext(MEMBER_A).firestore();
  const bDb = environment.authenticatedContext(MEMBER_B).firestore();
  const aCouple = ref(aDb, couplePath);
  const bCouple = ref(bDb, couplePath);
  await assertFails(deleteDoc(aCouple));
  await assertSucceeds(updateDoc(aCouple, { [`deleteApprovals.${MEMBER_A}`]: true }));
  await assertFails(deleteDoc(aCouple));
  await assertSucceeds(updateDoc(bCouple, { [`deleteApprovals.${MEMBER_B}`]: true }));
  await assertSucceeds(deleteDoc(bCouple));
});

test('activity entries cannot be authored as the other member', async () => {
  const db = environment.authenticatedContext(MEMBER_A).firestore();
  const activity = collection(db, couplePath, 'activity');
  await assertSucceeds(setDoc(doc(activity, 'valid-activity'), {
    actorUid: MEMBER_A,
    type: 'note_created',
    title: 'Sent a love note',
    summary: 'Thinking of you.',
    targetCollection: 'notes',
    targetId: 'note-1',
    createdAt: 1
  }));
  await assertFails(setDoc(doc(activity, 'spoofed-activity'), {
    actorUid: MEMBER_B,
    type: 'note_created',
    title: 'Sent a love note',
    summary: 'Impersonation attempt.',
    targetCollection: 'notes',
    targetId: 'note-2',
    createdAt: 2
  }));
});


test('activity history is append-only except after both partners approve deletion', async () => {
  const aDb = environment.authenticatedContext(MEMBER_A).firestore();
  const bDb = environment.authenticatedContext(MEMBER_B).firestore();
  const activity = ref(aDb, couplePath, 'activity', 'activity-one');
  await assertSucceeds(setDoc(activity, {
    actorUid: MEMBER_A, type: 'daily_answered', title: "Answered today's prompt",
    summary: 'Submit your own answer to reveal both responses.', targetCollection: 'daily',
    targetId: '2026-11-02', createdAt: 1
  }));
  await assertFails(updateDoc(activity, { title: 'Edited by a member' }));
  await assertFails(deleteDoc(activity));
  await assertSucceeds(updateDoc(ref(aDb, couplePath), { [`deleteApprovals.${MEMBER_A}`]: true }));
  await assertFails(deleteDoc(activity));
  await assertSucceeds(updateDoc(ref(bDb, couplePath), { [`deleteApprovals.${MEMBER_B}`]: true }));
  await assertSucceeds(deleteDoc(activity));
});

test('daily prompt documents cannot be changed and require mutual approval for deletion', async () => {
  const aDb = environment.authenticatedContext(MEMBER_A).firestore();
  const bDb = environment.authenticatedContext(MEMBER_B).firestore();
  const daily = ref(aDb, couplePath, 'daily', '2026-11-02');
  await assertFails(updateDoc(daily, { question: 'A different prompt' }));
  await assertFails(deleteDoc(daily));
  await assertSucceeds(updateDoc(ref(aDb, couplePath), { [`deleteApprovals.${MEMBER_A}`]: true }));
  await assertFails(deleteDoc(daily));
  await assertSucceeds(updateDoc(ref(bDb, couplePath), { [`deleteApprovals.${MEMBER_B}`]: true }));
  await assertSucceeds(deleteDoc(daily));
});

test('daily check-ins can only be written by their owner', async () => {
  const aDb = environment.authenticatedContext(MEMBER_A).firestore();
  const bDb = environment.authenticatedContext(MEMBER_B).firestore();
  const own = ref(aDb, couplePath, 'daily', '2026-11-02', 'checkins', MEMBER_A);
  await assertSucceeds(setDoc(own, { uid: MEMBER_A, mood: 'Loved', note: 'Thinking of you.', updatedAt: 1 }));
  await assertFails(setDoc(ref(aDb, couplePath, 'daily', '2026-11-02', 'checkins', MEMBER_B), {
    uid: MEMBER_B, mood: 'Happy', note: 'Impersonation attempt.', updatedAt: 2
  }));
  await assertSucceeds(getDoc(own));
  await assertSucceeds(getDoc(ref(bDb, couplePath, 'daily', '2026-11-02', 'checkins', MEMBER_A)));
});

test('final deletion approval allows cleanup of both partners daily records', async () => {
  const aDb = environment.authenticatedContext(MEMBER_A).firestore();
  const bDb = environment.authenticatedContext(MEMBER_B).firestore();
  const answerA = ref(aDb, couplePath, 'daily', '2026-11-02', 'answers', MEMBER_A);
  const answerB = ref(aDb, couplePath, 'daily', '2026-11-02', 'answers', MEMBER_B);
  const checkinA = ref(aDb, couplePath, 'daily', '2026-11-02', 'checkins', MEMBER_A);
  const checkinB = ref(aDb, couplePath, 'daily', '2026-11-02', 'checkins', MEMBER_B);

  await environment.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(ref(db, couplePath, 'daily', '2026-11-02', 'answers', MEMBER_A), {
      uid: MEMBER_A, answer: 'A', createdAt: 1, updatedAt: 1
    });
    await setDoc(ref(db, couplePath, 'daily', '2026-11-02', 'answers', MEMBER_B), {
      uid: MEMBER_B, answer: 'B', createdAt: 1, updatedAt: 1
    });
    await setDoc(ref(db, couplePath, 'daily', '2026-11-02', 'checkins', MEMBER_A), {
      uid: MEMBER_A, mood: 'Happy', note: '', updatedAt: 1
    });
    await setDoc(ref(db, couplePath, 'daily', '2026-11-02', 'checkins', MEMBER_B), {
      uid: MEMBER_B, mood: 'Loved', note: '', updatedAt: 1
    });
  });

  await assertFails(deleteDoc(answerB));
  await assertFails(deleteDoc(checkinB));
  await assertSucceeds(updateDoc(ref(aDb, couplePath), { [`deleteApprovals.${MEMBER_A}`]: true }));
  await assertFails(deleteDoc(answerB));
  await assertFails(deleteDoc(checkinB));
  await assertSucceeds(updateDoc(ref(bDb, couplePath), { [`deleteApprovals.${MEMBER_B}`]: true }));
  await assertSucceeds(deleteDoc(answerA));
  await assertSucceeds(deleteDoc(answerB));
  await assertSucceeds(deleteDoc(checkinA));
  await assertSucceeds(deleteDoc(checkinB));
});


test('live location can only be shared and removed by its owner', async () => {
  const aDb = environment.authenticatedContext(MEMBER_A).firestore();
  const bDb = environment.authenticatedContext(MEMBER_B).firestore();
  const outsiderDb = environment.authenticatedContext(OUTSIDER).firestore();
  const aLocation = ref(aDb, couplePath, 'locations', MEMBER_A);
  const bViewOfALocation = ref(bDb, couplePath, 'locations', MEMBER_A);

  await assertSucceeds(setDoc(aLocation, {
    uid: MEMBER_A,
    latitude: 14.5995,
    longitude: 120.9842,
    accuracyMeters: 18.0,
    updatedAt: serverTimestamp()
  }));
  await assertSucceeds(getDoc(bViewOfALocation));
  await assertFails(getDoc(ref(outsiderDb, couplePath, 'locations', MEMBER_A)));
  await assertFails(setDoc(ref(bDb, couplePath, 'locations', MEMBER_A), {
    uid: MEMBER_A,
    latitude: 0,
    longitude: 0,
    accuracyMeters: 5,
    updatedAt: serverTimestamp()
  }));
  await assertFails(deleteDoc(bViewOfALocation));
  await assertFails(setDoc(ref(aDb, couplePath, 'locations', MEMBER_A), {
    uid: MEMBER_A,
    latitude: 120,
    longitude: 0,
    accuracyMeters: 5,
    updatedAt: serverTimestamp()
  }));
  await assertSucceeds(deleteDoc(aLocation));
});


test('shared places are available to couple members and validated', async () => {
  const aDb = environment.authenticatedContext(MEMBER_A).firestore();
  const bDb = environment.authenticatedContext(MEMBER_B).firestore();
  const outsiderDb = environment.authenticatedContext(OUTSIDER).firestore();
  const placeA = ref(aDb, couplePath, 'places', 'home');
  const placeForB = ref(bDb, couplePath, 'places', 'home');
  const placeForOutsider = ref(outsiderDb, couplePath, 'places', 'home');

  await assertSucceeds(setDoc(placeA, {
    name: 'Home', latitude: 14.5995, longitude: 120.9842,
    radiusMeters: 150, createdBy: MEMBER_A, createdAt: 100
  }));
  await assertSucceeds(getDoc(placeForB));
  await assertFails(getDoc(placeForOutsider));
  await assertFails(setDoc(ref(aDb, couplePath, 'places', 'invalid-radius'), {
    name: 'Too small', latitude: 14.5, longitude: 121.0,
    radiusMeters: 20, createdBy: MEMBER_A, createdAt: 101
  }));
  await assertFails(setDoc(ref(bDb, couplePath, 'places', 'impersonated'), {
    name: 'Fake', latitude: 14.5, longitude: 121.0,
    radiusMeters: 150, createdBy: MEMBER_A, createdAt: 102
  }));
  await assertFails(setDoc(ref(outsiderDb, couplePath, 'places', 'outsider'), {
    name: 'Outside', latitude: 14.5, longitude: 121.0,
    radiusMeters: 150, createdBy: OUTSIDER, createdAt: 103
  }));
});

test('place transition events require a real shared place and truthful actor', async () => {
  const aDb = environment.authenticatedContext(MEMBER_A).firestore();
  const bDb = environment.authenticatedContext(MEMBER_B).firestore();
  await environment.withSecurityRulesDisabled(async (context) => {
    await setDoc(ref(context.firestore(), couplePath, 'places', 'school'), {
      name: 'School', latitude: 14.5, longitude: 121.0,
      radiusMeters: 300, createdBy: MEMBER_A, createdAt: 1
    });
  });
  const eventA = ref(aDb, couplePath, 'placeEvents', 'event-a1');
  await assertSucceeds(setDoc(eventA, {
    eventKey: 'event-a1', actorUid: MEMBER_A, placeId: 'school',
    placeName: 'School', transition: 'entered', createdAt: 200
  }));
  await assertSucceeds(getDoc(ref(bDb, couplePath, 'placeEvents', 'event-a1')));
  await assertFails(setDoc(ref(bDb, couplePath, 'placeEvents', 'fake-actor'), {
    eventKey: 'fake-actor', actorUid: MEMBER_A, placeId: 'school',
    placeName: 'School', transition: 'left', createdAt: 201
  }));
  await assertFails(setDoc(ref(aDb, couplePath, 'placeEvents', 'missing-place'), {
    eventKey: 'missing-place', actorUid: MEMBER_A, placeId: 'missing',
    placeName: 'Nowhere', transition: 'entered', createdAt: 202
  }));
  await assertFails(setDoc(ref(aDb, couplePath, 'placeEvents', 'bad-transition'), {
    eventKey: 'bad-transition', actorUid: MEMBER_A, placeId: 'school',
    placeName: 'School', transition: 'visited', createdAt: 203
  }));
});


test('Live Photo Widget photos use Firestore Blobs and only the active pointer protects deletion', async () => {
  const aDb = environment.authenticatedContext(MEMBER_A).firestore();
  const bDb = environment.authenticatedContext(MEMBER_B).firestore();
  const outsiderDb = environment.authenticatedContext(OUTSIDER).firestore();

  const photo = ref(aDb, couplePath, 'livePhotoWidgetPhotos', 'photo-one');
  const image = ref(aDb, couplePath, 'livePhotoWidgetPhotos', 'photo-one', 'content', 'image');
  const thumbnail = ref(aDb, couplePath, 'livePhotoWidgetPhotos', 'photo-one', 'content', 'thumbnail');
  const current = ref(aDb, couplePath, 'livePhotoWidget', 'current');

  await assertSucceeds(setDoc(photo, {
    createdBy: MEMBER_A,
    createdAt: 100,
    status: 'pending',
    sizeBytes: 0,
    thumbnailBytes: 0
  }));
  await assertSucceeds(setDoc(image, {
    image: Bytes.fromUint8Array(new Uint8Array([1, 2, 3])),
    contentType: 'image/jpeg',
    createdBy: MEMBER_A,
    createdAt: 100
  }));
  await assertSucceeds(setDoc(thumbnail, {
    image: Bytes.fromUint8Array(new Uint8Array([4, 5, 6])),
    contentType: 'image/jpeg',
    createdBy: MEMBER_A,
    createdAt: 100
  }));

  const publish = writeBatch(aDb);
  publish.update(photo, {
    status: 'ready',
    sizeBytes: 3,
    thumbnailBytes: 3,
    previousPhotoId: ''
  });
  publish.set(current, {
    activePhotoId: 'photo-one',
    updatedBy: MEMBER_A,
    updatedAt: 101
  });
  await assertSucceeds(publish.commit());

  await assertSucceeds(getDoc(ref(bDb, couplePath, 'livePhotoWidget', 'current')));
  await assertSucceeds(getDoc(ref(bDb, couplePath, 'livePhotoWidgetPhotos', 'photo-one', 'content', 'image')));
  await assertFails(getDoc(ref(outsiderDb, couplePath, 'livePhotoWidgetPhotos', 'photo-one')));

  await assertFails(deleteDoc(ref(bDb, couplePath, 'livePhotoWidgetPhotos', 'photo-one', 'content', 'image')));
  await assertFails(updateDoc(ref(bDb, couplePath, 'livePhotoWidget', 'current'), {
    activePhotoId: 'missing-photo',
    updatedBy: MEMBER_B,
    updatedAt: 102
  }));

  await assertSucceeds(updateDoc(ref(bDb, couplePath, 'livePhotoWidget', 'current'), {
    activePhotoId: '',
    updatedBy: MEMBER_B,
    updatedAt: 103
  }));
  await assertSucceeds(deleteDoc(ref(bDb, couplePath, 'livePhotoWidgetPhotos', 'photo-one', 'content', 'image')));
  await assertSucceeds(deleteDoc(ref(bDb, couplePath, 'livePhotoWidgetPhotos', 'photo-one', 'content', 'thumbnail')));
  await assertSucceeds(deleteDoc(ref(bDb, couplePath, 'livePhotoWidgetPhotos', 'photo-one')));
});


test('Live Photo Widget state is removed only after both partners approve permanent deletion', async () => {
  const aDb = environment.authenticatedContext(MEMBER_A).firestore();
  const bDb = environment.authenticatedContext(MEMBER_B).firestore();
  const state = ref(aDb, couplePath, 'livePhotoWidget', 'current');

  await assertSucceeds(setDoc(state, {
    activePhotoId: '',
    updatedBy: MEMBER_A,
    updatedAt: 100
  }));
  await assertFails(deleteDoc(state));
  await assertSucceeds(updateDoc(ref(aDb, couplePath), {
    [`deleteApprovals.${MEMBER_A}`]: true
  }));
  await assertFails(deleteDoc(state));
  await assertSucceeds(updateDoc(ref(bDb, couplePath), {
    [`deleteApprovals.${MEMBER_B}`]: true
  }));
  await assertSucceeds(deleteDoc(state));
});
