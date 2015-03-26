(function () {
   'use strict';
}());

angular.module('slicebox.adminBoxes', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/admin/boxes', {
    templateUrl: '/assets/partials/adminBoxes.html',
    controller: 'AdminBoxesCtrl'
  });
})

.controller('AdminBoxesCtrl', function($scope, $http, $mdDialog, $q, $interval, openConfirmationDeleteModal) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: confirmDeleteBoxes
            }
        ];

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.boxesTable)) {
            $scope.callbacks.boxesTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    // Scope functions
    $scope.loadBoxesPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/boxes');
    };

    $scope.addBoxButtonClicked = function() {
        $mdDialog.show({
                templateUrl: '/assets/partials/addBoxModalContent.html',
                controller: 'AddBoxModalCtrl'
            }).then(function() {
                $scope.callbacks.boxesTable.reloadPage();
            });
    };

    // Private functions
    function confirmDeleteBoxes(boxes) {
        var deleteConfirmationText = 'Permanently delete ' + boxes.length + ' boxes?';

        return openConfirmationDeleteModal('Delete Boxes', deleteConfirmationText, function() {
            return deleteBoxes(boxes);
        });
    }

    function deleteBoxes(boxes) {
        var deletePromises = [];
        var deletePromise;

        angular.forEach(boxes, function(box) {
            deletePromise = $http.delete('/api/boxes/' + box.id);
            deletePromises.push(deletePromise);
        });

        return $q.all(deletePromises);
    }
})

.controller('AddBoxModalCtrl', function($scope, $mdDialog, $http) {
    // Initialization
    $scope.uiState = {
        addChoice: 'generateURL',
        errorMessage: null
    };

    // Scope functions
    $scope.radioButtonChanged = function() {
        $scope.addBoxForm.$setPristine();
    };

    $scope.generateURLButtonClicked = function() {
        if ($scope.addBoxForm.$invalid) {
            return;
        }

        $scope.uiState.errorMessage = null;

        var generateURLPromise = $http.post('/api/boxes/generatebaseurl', {value: $scope.uiState.remoteBoxName});

        generateURLPromise.success(function(data) {
            showBaseURLDialog(data.value);
            $mdDialog.hide();
        });

        generateURLPromise.error(function(data) {
            $scope.uiState.errorMessage = data;
        });

        return generateURLPromise;
    };

    $scope.connectButtonClicked = function() {
        if ($scope.addBoxForm.$invalid) {
            return;
        }

        $scope.uiState.errorMessage = null;

        var connectPromise = $http.post('/api/boxes/addremotebox',
            {
                name: $scope.uiState.remoteBoxName,
                baseUrl: $scope.uiState.connectionURL
            });

        connectPromise.success(function(data) {
            $mdDialog.hide();
        });

        connectPromise.error(function(data) {
            $scope.uiState.errorMessage = data;
        });

        return connectPromise;
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };

    // Private functions
    function showBaseURLDialog(baseURL) {
        $mdDialog.show({
                templateUrl: '/assets/partials/baseURLModalContent.html',
                controller: 'BaseURLModalCtrl',
                locals: {
                    baseURL: baseURL
                }
            });
    }
})

.controller('BaseURLModalCtrl', function($scope, $mdDialog, baseURL) {
    // Initialization
    $scope.baseURL = baseURL;

    // Scope functions
    $scope.mailBody = function() {
        var bodyText = 'Box connection URL:\n\n' + baseURL;

        return encodeURIComponent(bodyText);
    };

    $scope.closeButtonClicked = function() {
        $mdDialog.cancel();
    };
});