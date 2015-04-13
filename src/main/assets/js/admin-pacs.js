(function () {
   'use strict';
}());

angular.module('slicebox.adminPacs', ['ngRoute'])

.config(function($routeProvider) {
    $routeProvider.when('/admin/pacs', {
        templateUrl: '/assets/partials/adminPacs.html',
        controller: 'AdminPacsCtrl'
    });
})

.controller('AdminPacsCtrl', function($scope, $http, $mdDialog, $q, $log, openConfirmationDeleteModal) {
})

.controller('AdminScpsCtrl', function($scope, $http, $mdDialog, $q, $log, openConfirmationDeleteModal) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: confirmDeleteScps
            }
        ];

    $scope.callbacks = {};

    $scope.uiState.addScpInProgress = false;
  
    // Scope functions
    $scope.loadScpsPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/scps');
    };

    $scope.addScpButtonClicked = function() {
        var dialogPromise = $mdDialog.show({
                templateUrl: '/assets/partials/addScpModalContent.html',
                controller: 'AddScpModalCtrl'
            });

        dialogPromise.then(function (scp) {
            $scope.uiState.addDirectoryInProgress = true;

            var addScpPromise = $http.post('/api/scps', scp);
            addScpPromise.error(function(data) {
                $scope.showErrorMessage(data);
            });

            addScpPromise.success(function() {
                $scope.showInfoMessage("SCP added");                
            });

            addScpPromise.finally(function() {
                $scope.uiState.addScpInProgress = false;
                $scope.callbacks.scpsTable.reloadPage();
            });
        });
    };

    // Private functions
    function confirmDeleteScps(scpObjects) {
        var deleteConfirmationText = 'Permanently delete ' + scpObjects.length + ' SCPs?';

        return openConfirmationDeleteModal('Delete SCPs', deleteConfirmationText, function() {
            return deleteScps(scpObjects);
        });
    }

    function deleteScps(scpObjects) {
        var removePromises = [];
        var removePromise;

        angular.forEach(scpObjects, function(scpObject) {
            removePromise = $http.delete('/api/scps/' + scpObject.id);
            removePromises.push(removePromise);
        });

        return $q.all(removePromises);
    }
})

.controller('AddScpModalCtrl', function($scope, $mdDialog) {

    // Scope functions
    $scope.addButtonClicked = function() {
        $mdDialog.hide({ name: $scope.name, aeTitle: $scope.aeTitle, port: $scope.port });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
})

.controller('AdminScusCtrl', function($scope, $http, $mdDialog, $q, $log, openConfirmationDeleteModal) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: confirmDeleteScus
            }
        ];

    $scope.callbacks = {};

    $scope.uiState.addScuInProgress = false;
  
    // Scope functions
    $scope.loadScusPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/scus');
    };

    $scope.addScuButtonClicked = function() {
        var dialogPromise = $mdDialog.show({
                templateUrl: '/assets/partials/addScuModalContent.html',
                controller: 'AddScuModalCtrl'
            });

        dialogPromise.then(function (scu) {
            $scope.uiState.addDirectoryInProgress = true;

            var addScuPromise = $http.post('/api/scus', scu);
            addScuPromise.error(function(data) {
                $scope.showErrorMessage(data);
            });

            addScuPromise.success(function() {
                $scope.showInfoMessage("SCU added");                
            });

            addScuPromise.finally(function() {
                $scope.uiState.addScuInProgress = false;
                $scope.callbacks.scusTable.reloadPage();
            });
        });
    };

    // Private functions
    function confirmDeleteScus(scuObjects) {
        var deleteConfirmationText = 'Permanently delete ' + scuObjects.length + ' SCUs?';

        return openConfirmationDeleteModal('Delete SCUs', deleteConfirmationText, function() {
            return deleteScus(scuObjects);
        });
    }

    function deleteScus(scuObjects) {
        var removePromises = [];
        var removePromise;

        angular.forEach(scuObjects, function(scuObject) {
            removePromise = $http.delete('/api/scus/' + scuObject.id);
            removePromises.push(removePromise);
        });

        return $q.all(removePromises);
    }
})

.controller('AddScuModalCtrl', function($scope, $mdDialog) {

    // Scope functions
    $scope.addButtonClicked = function() {
        $mdDialog.hide({ name: $scope.name, aeTitle: $scope.aeTitle, host: $scope.host, port: $scope.port });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
});
