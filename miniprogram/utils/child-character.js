// Display only: the server-provided gender never changes permissions or student data.
module.exports = function childCharacter(gender) {
  const value = typeof gender === 'string' ? gender.trim().toUpperCase() : '';
  const kind = value === '男' || value === 'MALE' ? 'boy' : value === '女' || value === 'FEMALE' ? 'girl' : 'neutral';
  return '/assets/parent/' + kind + '.png';
};
