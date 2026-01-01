const GRID_SIZE = 8;
let mapData = []; 
let selectedType = 'MY_TILE';

function initMap() {
    const gridElement = document.getElementById('map-grid');
    for (let y = 0; y < GRID_SIZE; y++) {
        for (let x = 0; x < GRID_SIZE; x++) {
            const tile = document.createElement('div');
            tile.classList.add('tile');
            tile.id = `tile-${x}-${y}`;
            if (x < 4) tile.onclick = () => handleTileClick(x, y);
            else tile.classList.add('symmetric-zone');
            gridElement.appendChild(tile);
            mapData.push({ x, y, tileType: 'EMPTY' });
        }
    }
}

function handleTileClick(x, y) {
    const type = selectedType;
    // 내 영역 업데이트
    updateTile(x, y, type);

    // 상대 영역 대칭 및 치환 (Mirror & Swap)
    const symX = 7 - x;
    let symType = type;
    if (type === 'MY_TILE') symType = 'ENEMY_TILE';
    else if (type === 'ENEMY_TILE') symType = 'MY_TILE';

    updateTile(symX, y, symType);
}

function updateTile(x, y, type) {
    const tileObj = mapData.find(t => t.x === x && t.y === y);
    tileObj.tileType = type;
    const el = document.getElementById(`tile-${x}-${y}`);
    el.className = `tile ${type} ${x >= 4 ? 'symmetric-zone' : ''}`;
    el.innerText = type === 'EMPTY' ? '' : (type === 'MY_TILE' ? '내꺼' : (type === 'ENEMY_TILE' ? '적꺼' : '벽'));
}

function selectType(type, e) {
    selectedType = type;
    document.querySelectorAll('.tool-btn').forEach(b => b.classList.remove('active'));
    e.target.classList.add('active');
}

async function saveMap() {
    const halfMap = mapData.filter(t => t.x < 4);
    await fetch(`/api/map/save?uid=player1`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(halfMap)
    });
    alert("저장 완료!");
}

initMap();
